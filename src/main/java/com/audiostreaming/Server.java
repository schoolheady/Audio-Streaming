package com.audiostreaming;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Audio streaming server that forwards UDP audio frames between clients and
 * maintains a TCP control channel for registration, presence, and commands
 * such as JOIN, LEAVE, MUTE, and UNMUTE.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Accept TCP connections and register clients, assigning incremental IDs</li>
 *   <li>Track client presence and state (ACTIVE, MUTED, LEFT, DISCONNECTED)</li>
 *   <li>Receive UDP audio packets from senders and forward in-order frames to other clients</li>
 *   <li>Run periodic heartbeats to detect stale TCP connections and time out inactive clients</li>
 * </ul>
 * Concurrency model:
 * <ul>
 *   <li>One thread accepting TCP control connections</li>
 *   <li>One background scheduler for heartbeats and cleanup</li>
 *   <li>A worker pool for UDP packet processing and forwarding</li>
 * </ul>
 */
public class Server{
    private final DatagramSocket socket; 
    private static final Logger logger = Logger.getLogger(Server.class.getName());

    private static final CopyOnWriteArrayList<Socket> tcpClients = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Socket, Integer> tcpSocketToClientId = new ConcurrentHashMap<>();

    private final ExecutorService workerPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    final ConcurrentHashMap<Integer, ClientState> clientStates;

    // server-assigned client id generator
    private final java.util.concurrent.atomic.AtomicInteger nextClientId = new java.util.concurrent.atomic.AtomicInteger(1);
    private static final int MAX_PACKET_SIZE = 1500;
    private static final int MAX_BUFFERED_PACKETS = 200; // per-client buffer cap

    private final java.util.concurrent.ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final long HEARTBEAT_INTERVAL_MS = 2_000; // 2s
    static final long CLIENT_TIMEOUT_MS = 10_000; // 10s without packets -> DISCONNECTED
    // removal after extended grace period (5x timeout)
    private static final long CLIENT_REMOVAL_MS = CLIENT_TIMEOUT_MS * 5;

    // tcp acceptor socket
    private ServerSocket tcpServerSocket;

    /**
     * Creates a server instance bound to the provided UDP socket.
     *
     * @param socket the UDP socket used to receive and forward audio packets
     * @throws Exception if initialization fails
     */
    public Server(DatagramSocket socket) throws Exception{
        this.clientStates = new ConcurrentHashMap<>();
        this.socket = socket;
    }

    // Broadcast a one-line message to all connected TCP clients (best-effort)
    private void sendTcpMessageToAll(String message) {
        for (Socket s : new ArrayList<>(tcpClients)) {
            try {
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
                w.write(message + "\n");
                w.flush();
            } catch (IOException e) {
                // ignore individual client failures
            }
        }
    }


    /**
     * Starts the TCP control server, binding to port 4444 by default.
     * <p>
     * If port 4444 is unavailable, the server falls back to an ephemeral port
     * assigned by the OS. The actual port in use can be queried via {@link #getTcpPort()}.
     * </p>
     *
     * @throws Exception if the server socket cannot be created or bound
     */
    public void startTCPServer() throws Exception{
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        try {
            ss.bind(new InetSocketAddress(4444));
        } catch (java.net.BindException be) {
            logger.log(Level.WARNING, "[TCP] - Port 4444 unavailable, falling back to ephemeral port", be);
            ss.bind(new InetSocketAddress(0));
        }
        startTCPServer(ss);
    }

    /**
     * Starts the TCP acceptor loop using the provided, pre-bound server socket.
     * <p>
     * Useful for tests that need to bind to an ephemeral port first and then delegate to the
     * server to run the accept loop.
     * </p>
     *
     * @param providedSocket a server socket that is already created and bound
     * @throws Exception if the accept loop fails to start or runtime errors occur
     */
    public void startTCPServer(ServerSocket providedSocket) throws Exception {
        this.tcpServerSocket = providedSocket;
        logger.info("[SERVER] - Server starting TCP acceptor");
        
        // Show all IP addresses the server is listening on
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            System.out.println("==============================================");
            System.out.println("[SERVER] - Server is running on the following IP addresses:");
            System.out.println("[SERVER] - TCP Port: " + tcpServerSocket.getLocalPort());
            System.out.println("[SERVER] - UDP Port: " + socket.getLocalPort());
            System.out.println("----------------------------------------------");
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                            System.out.println("[SERVER] - " + addr.getHostAddress());
                        }
                    }
                }
            }
            System.out.println("==============================================");
        } catch (Exception e) {
            System.out.println("[SERVER] - Could not enumerate IP addresses: " + e.getMessage());
        }
        
        logger.info("[TCP] - Listening for TCP clients on port " + tcpServerSocket.getLocalPort());

        new Thread(() -> {
            try {
                tcpAcceptLoop();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[TCP] - accept loop error", e);
            }
        }, "tcp-accept-thread").start();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                heartbeat();
            } catch (Exception e) {
                logger.log(Level.WARNING, "[HEARTBEAT] - error", e);
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Accept loop for TCP control connections; assumes {@code tcpServerSocket} is initialized
     * and bound. Spawns a {@code ControlHandler} per accepted client.
     *
     * @throws Exception if the accept loop encounters unrecoverable errors
     */
    private void tcpAcceptLoop() throws Exception {
        try {
            while (!tcpServerSocket.isClosed()) {
                try {
                    Socket tcpSocket = tcpServerSocket.accept(); // blocking
                    logger.info("[TCP] - Accepted connection from " + tcpSocket.getRemoteSocketAddress());

                    // add to tcpClients list
                    tcpClients.add(tcpSocket);

                    // spawn a control handler for this socket and return immediately to accept more
                    ControlHandler handler = new ControlHandler(tcpSocket);
                    Thread t = new Thread(handler, "control-handler-" + tcpSocket.getPort());
                    t.setDaemon(true);
                    t.start();

                } catch (SocketException se) {
                    // server socket was closed, break out
                    if (tcpServerSocket.isClosed()) break;
                    logger.log(Level.WARNING, "[SERVER] - TCP accept SocketException", se);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "[SERVER] - TCP accept error", e);
                }
            }
        } finally {
            try { if (tcpServerSocket != null && !tcpServerSocket.isClosed()) tcpServerSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Periodic maintenance task that:
     * <ul>
     *   <li>Marks clients DISCONNECTED after a timeout with no UDP packets</li>
     *   <li>Removes long-disconnected clients after a grace period</li>
     *   <li>Sends a lightweight heartbeat over TCP to detect closed client sockets</li>
     * </ul>
     *
     * @throws Exception on I/O or scheduling errors
     */
    public void heartbeat() throws Exception{
    long now = System.currentTimeMillis();
        for (Map.Entry<Integer, ClientState> e : clientStates.entrySet()) {
            Integer id = e.getKey();
            ClientState st = e.getValue();
            if (st == null) continue;
            synchronized (st) {
                if (st.lastHeard > 0 && now - st.lastHeard > CLIENT_TIMEOUT_MS) {
                    if (st.status != ClientStatus.DISCONNECTED && st.status != ClientStatus.LEFT) {
                        logger.info("[HEARTBEAT] - Marking clientId=" + st.clientId + " DISCONNECTED due to timeout");
                        st.status = ClientStatus.DISCONNECTED;
                    }
                }

                if (st.status == ClientStatus.DISCONNECTED && now - st.lastHeard > CLIENT_REMOVAL_MS) {
                    logger.info("[HEARTBEAT] - Removing stale clientId=" + id + " after grace period");
                    clientStates.remove(id);
                    tcpSocketToClientId.values().removeIf(v -> v.equals(id));
                }
            }
        }

    List<Socket> snapshot = new ArrayList<>(tcpClients);

        for (Socket client : snapshot) {
            try {
                // Send a heartbeat message to the client to detect closed connections
                OutputStream out = client.getOutputStream();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out));
                writer.write("HEARTBEAT\n");
                writer.flush();
                } catch (IOException e) {
                logger.log(Level.INFO, "Client disconnected (TCP): " + client.getRemoteSocketAddress(), e);
                // mark associated clientId as DISCONNECTED and remove tcp client mapping
                Integer clientId = tcpSocketToClientId.remove(client);
                if (clientId != null) {
                    ClientState st = clientStates.get(clientId);
                    if (st != null) {
                        st.status = ClientStatus.DISCONNECTED;
                    }
                }
                try { client.close(); } catch (IOException ignored) {}
                tcpClients.remove(client);
            }
        }
    }

    /**
     * Receives UDP packets from clients in a loop and dispatches processing to the worker pool.
     * <p>
     * Exits gracefully when the UDP socket is closed as part of shutdown.
     * </p>
     *
     * @throws Exception if UDP receive or task submission fails
     */
    public void udpReceive() throws Exception{ 
        try {
            while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                byte[] buf = new byte[MAX_PACKET_SIZE];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketException se) {
                    // If socket was closed as part of shutdown, exit gracefully
                    if (socket.isClosed()) {
                        logger.info("[UDP] - Socket closed, stopping udpReceive");
                        break;
                    }
                    throw se;
                }

                // copy only valid bytes
                byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
                InetAddress srcAddr = packet.getAddress();
                int srcPort = packet.getPort();
                logger.fine("[SERVER] - Received packet from " + srcAddr + ":" + srcPort + " with length " + copy.length);
                logger.fine("[UDP] - Submitting work to workerPool for " + srcAddr + ":" + srcPort);
                try {
                    workerPool.submit(() -> {
                        logger.fine("[UDP] - Worker start for " + srcAddr + ":" + srcPort + " on thread " + Thread.currentThread().getName());
                        try {
                            processPacket(copy, srcAddr, srcPort);
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "[UDP] - Worker caught exception while processing packet from " + srcAddr + ":" + srcPort, e);
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException rex) {
                    logger.log(Level.WARNING, "[UDP] - Task submission rejected", rex);
                }
            }
        } finally {
            logger.info("[UDP] - udpReceive exiting");
        }
    }

    /**
     * Stops the server, closing TCP and UDP sockets, terminating background tasks,
     * and clearing client state/mappings.
     */
    public void stop() {
    logger.info("[SERVER] - Stopping server...");
        // close tcp acceptor first so accept loop can exit
        try {
            if (tcpServerSocket != null && !tcpServerSocket.isClosed()) tcpServerSocket.close();
        } catch (Exception ignored) {}
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception e) {
            // ignore
        }
        try {
            scheduler.shutdownNow();
        } catch (Exception ignored) {}

        try {
            workerPool.shutdownNow();
        } catch (Exception ignored) {}

        try {
            // wait a short time for shutdown
            scheduler.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            workerPool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // clear tcp client references
        tcpClients.forEach(s -> { try { s.close(); } catch (IOException ignored) {} });
        tcpClients.clear();
        tcpSocketToClientId.clear();
        logger.info("[SERVER] - Stopped");
    }


    /**
     * Starts a TCP accept loop by creating and binding a new {@link ServerSocket}.
     * <p>
     * Similar to {@link #startTCPServer()}, but performs the bind inside this method
     * and then runs the accept loop.
     * </p>
     *
     * @throws Exception if binding or accepting connections fails
     */
    public void tcpConnection() throws Exception{
    // create unbound ServerSocket and set reuse before bind to avoid bind races in tests
    tcpServerSocket = new ServerSocket();
    tcpServerSocket.setReuseAddress(true);
    try {
        tcpServerSocket.bind(new InetSocketAddress(4444));
        logger.info("[TCP] - Listening for TCP clients on port 4444");
    } catch (java.net.BindException be) {
        logger.log(Level.WARNING, "[TCP] - Port 4444 unavailable in tcpConnection(), falling back to ephemeral port", be);
        tcpServerSocket.bind(new InetSocketAddress(0));
        logger.info("[TCP] - Listening for TCP clients on ephemeral port " + tcpServerSocket.getLocalPort());
    }

        try {
            while (!tcpServerSocket.isClosed()) {
                try {
                    Socket tcpSocket = tcpServerSocket.accept(); // blocking
                    logger.info("[TCP] - Accepted connection from " + tcpSocket.getRemoteSocketAddress());

                    // add to tcpClients list
                    tcpClients.add(tcpSocket);

                    // spawn a control handler for this socket and return immediately to accept more
                    ControlHandler handler = new ControlHandler(tcpSocket);
                    Thread t = new Thread(handler, "control-handler-" + tcpSocket.getPort());
                    t.setDaemon(true);
                    t.start();

                } catch (SocketException se) {
                    // server socket was closed, break out
                    if (tcpServerSocket.isClosed()) break;
                    logger.log(Level.WARNING, "[SERVER] - TCP accept SocketException", se);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "[SERVER] - TCP accept error", e);
                }
            }
        } finally { // gracefully close server socket
            try { if (tcpServerSocket != null && !tcpServerSocket.isClosed()) tcpServerSocket.close(); } catch (IOException ignored) {}
        }
    }

    // Expose the actual TCP listening port (useful for tests when server binds to ephemeral port)
    /**
     * Returns the TCP port the control server is currently listening on.
     *
     * @return the bound TCP port, or -1 if the TCP server is not running
     */
    public int getTcpPort() {
        return tcpServerSocket == null ? -1 : tcpServerSocket.getLocalPort();
    }

    private class ControlHandler implements Runnable {
        private final Socket tcpSocket;

        ControlHandler(Socket tcpSocket) {
            this.tcpSocket = tcpSocket;
        }

        @Override
        // handle registration and subsequent commands from this client
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(tcpSocket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8))) {

                // read registration line (expected: REGISTER <clientId> <udpPort>)
                String reg = reader.readLine();
                if (reg == null || !reg.startsWith("REGISTER")) {
                    writer.write("ERROR expected REGISTER\n");
                    writer.flush();
                    try { tcpSocket.close(); } catch (IOException ignored) {}
                    return;
                }

                String[] parts = reg.trim().split("\\s+");
                int udpPort;
                String username = null;
                try {
                    if (parts.length < 2) {
                        writer.write("ERROR invalid register\n");
                        writer.flush();
                        try { tcpSocket.close(); } catch (IOException ignored) {}
                        return;
                    }
                    udpPort = Integer.parseInt(parts[1]);
                    username = parts.length >= 3 ? parts[2] : ("user" + System.currentTimeMillis()%1000);
                } catch (NumberFormatException ex) {
                    writer.write("ERROR invalid register\n");
                    writer.flush();
                    try { tcpSocket.close(); } catch (IOException ignored) {}
                    return;
                }

                // assign or reuse server id.
                // If a prior client state exists for the same IP and UDP port, reuse it so rejoining
                // the same endpoint does not create duplicate client records.
                InetAddress regAddr = tcpSocket.getInetAddress();
                Integer clientId = null;
                for (Map.Entry<Integer, ClientState> e2 : clientStates.entrySet()) {
                    ClientState existing = e2.getValue();
                    if (existing != null && existing.clientAddress != null && existing.clientAddress.equals(regAddr) && existing.clientPort == udpPort) {
                        clientId = e2.getKey();
                        break;
                    }
                }

                if (clientId == null) {
                    clientId = nextClientId.getAndIncrement();
                }

                // map tcp socket to client id
                tcpSocketToClientId.put(tcpSocket, clientId);

                // Get or create client state first (we need to know the old username before duplicate checking)
                ClientState st = clientStates.computeIfAbsent(clientId, id -> new ClientState());
                String oldUsername = st.username; // preserve old username in case of re-registration

                // Handle duplicate usernames: check if username already exists and append ID if needed
                // Skip both the current clientId AND its old username when checking for duplicates
                String finalUsername = username;
                int nameCount = 0;
                boolean nameExists = true;
                while (nameExists) {
                    nameExists = false;
                    for (Map.Entry<Integer, ClientState> entry : clientStates.entrySet()) {
                        if (entry.getKey().equals(clientId)) continue; // skip self
                        ClientState cs = entry.getValue();
                        if (cs != null && cs.username != null && cs.username.equals(finalUsername)) {
                            nameExists = true;
                            nameCount++;
                            finalUsername = username + "#" + nameCount;
                            break;
                        }
                    }
                }

                // populate or update client state
                st.clientId = clientId;
                st.clientAddress = regAddr;
                st.clientPort = udpPort;
                st.username = finalUsername;
                // Reset sequencing and buffers on fresh registration so old expectedSeq doesn't block forwarding
                st.lastHeard = System.currentTimeMillis();
                st.expectedSeq = 0;
                st.buffer.clear();
                st.status = ClientStatus.ACTIVE;

                // Reply with the assigned client id so client knows it
                writer.write("OK " + clientId + "\n");
                writer.flush();
                logger.info("[TCP] - Registered clientId=" + clientId + " udpPort=" + udpPort + " from " + tcpSocket.getRemoteSocketAddress());

                // Notify other TCP clients about this new presence
                sendTcpMessageToAll("PRESENCE ADD " + clientId + " " + st.username);

                // Send existing presence list to the newly registered client
                // Only include clients that are currently ACTIVE or MUTED (in the call)
                for (Map.Entry<Integer, ClientState> entry : clientStates.entrySet()) {
                    if (entry.getKey().equals(clientId)) continue;
                    ClientState cs = entry.getValue();
                    if (cs != null && (cs.status == ClientStatus.ACTIVE || cs.status == ClientStatus.MUTED)) {
                        String msg = "PRESENCE ADD " + cs.clientId + " " + (cs.username == null ? "" : cs.username);
                        writer.write(msg + "\n");
                        logger.info("[TCP] - Sent to clientId=" + clientId + ": " + msg);
                    } else if (cs != null) {
                        logger.info("[TCP] - Skipped sending PRESENCE ADD for clientId=" + cs.clientId + " (status=" + cs.status + ") to clientId=" + clientId);
                    }
                }
                writer.flush();

                // handle commands from this client until disconnect
                // make the control socket read interrupt-friendly by using a SO_TIMEOUT
                try {
                    tcpSocket.setSoTimeout(2000); // 2s timeout so we can check interrupted/shutdown
                } catch (Exception e) {
                    logger.log(Level.FINE, "Failed to set SO_TIMEOUT on control socket", e);
                }

                String line;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        line = reader.readLine();
                        if (line == null) break; // client closed
                        String cmd = line.trim();
                        logger.fine("[TCP] - Received command from " + clientId + ": " + cmd);
                        try {
                            if ("SYNC".equals(cmd)) {
                                // Respond only to this client with current ACTIVE/MUTED presence
                                for (Map.Entry<Integer, ClientState> entry : clientStates.entrySet()) {
                                    if (entry.getKey().equals(clientId)) continue; // skip self
                                    ClientState cs = entry.getValue();
                                    if (cs != null && (cs.status == ClientStatus.ACTIVE || cs.status == ClientStatus.MUTED)) {
                                        writer.write("PRESENCE ADD " + cs.clientId + " " + (cs.username == null ? "" : cs.username) + "\n");
                                    }
                                }
                                writer.flush();
                            } else {
                                handleCommands(cmd, clientId);
                            }
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "[TCP] - Error handling command from " + clientId, e);
                        }
                    } catch (java.net.SocketTimeoutException ste) {
                        // timeout periodically so we can respond to interrupts/shutdown
                        if (Thread.currentThread().isInterrupted()) break;
                        continue;
                    }
                }

            } catch (IOException e) {
                System.out.println("[TCP] - ControlHandler IO error: " + e.getMessage());
            } finally {
                // cleanup on disconnect
                Integer removed = tcpSocketToClientId.remove(tcpSocket);
                if (removed != null) {
                    ClientState st = clientStates.get(removed);
                    if (st != null) {
                        // Only send PRESENCE REMOVE if the client was previously in the call (ACTIVE or MUTED)
                        // Don't send duplicate PRESENCE REMOVE if already LEFT
                        boolean wasInCall = (st.status == ClientStatus.ACTIVE || st.status == ClientStatus.MUTED);
                        st.status = ClientStatus.DISCONNECTED;
                        if (wasInCall) {
                            sendTcpMessageToAll("PRESENCE REMOVE " + removed);
                        }
                    }
                }
                try { tcpSocket.close(); } catch (IOException ignored) {}
                tcpClients.remove(tcpSocket);
                System.out.println("[TCP] - ControlHandler exiting for " + tcpSocket.getRemoteSocketAddress());
            }
        }
    }

    /**
     * Handles a single control command for the specified client, updating its state
     * and broadcasting presence or state changes as needed.
     *
     * @param command the command string (e.g., MUTE, UNMUTE, JOIN, LEAVE)
     * @param clientId the target client ID
     * @throws Exception if command processing encounters an error
     */
    void handleCommands(String command, int clientId) throws Exception{
        ClientState st = clientStates.get(clientId);
        if (st == null) {
            System.out.println("[SERVER] - Command for unknown clientId=" + clientId + ": " + command);
            return;
        }

        switch (command) {
            case "MUTE" -> {
                synchronized (st) {
                    st.status = ClientStatus.MUTED; 
                }
                System.out.println("[TCP] - Client " + clientId + " muted");
                // Broadcast mute state so UIs can reflect the authoritative server state
                sendTcpMessageToAll("MUTE " + clientId);
            }
            case "LEAVE" -> {
                synchronized (st) {
                    st.status = ClientStatus.LEFT;
                }
                // mark client as LEFT but preserve the tcp socket mapping so the client
                // can leave and later re-join the call without reconnecting to TCP.
                System.out.println("[TCP] - Client " + clientId + " left the call (state preserved for rejoin)");
                sendTcpMessageToAll("PRESENCE REMOVE " + clientId);
            }
            case "UNMUTE" -> {
                synchronized (st) {
                    st.status = ClientStatus.ACTIVE;
                }
                System.out.println("[TCP] - Client " + clientId + " unmuted");
                // Broadcast unmute state so UIs can reflect the authoritative server state
                sendTcpMessageToAll("UNMUTE " + clientId);
            }
            case "JOIN" -> {
                    boolean wasLeft = false;
                synchronized (st) {
                        wasLeft = (st.status == ClientStatus.LEFT);
                    st.status = ClientStatus.ACTIVE;
                }
                System.out.println("[TCP] - Client " + clientId + " joined (ACTIVE)");
                    // Only broadcast PRESENCE ADD if client is rejoining after leaving
                    // (initial join already broadcasts PRESENCE ADD during REGISTER)
                    if (wasLeft) {
                        sendTcpMessageToAll("PRESENCE ADD " + clientId + " " + st.username);
                    }
            }
            default -> System.out.println("[SERVER] - Unknown command: " + command);
        }
    // handle server commands from clients (mute/leave/join handled above)

    }

    // helper methods removed — command handling is done in handleCommands()
    
    /**
     * Processes a single UDP audio packet: performs basic validation, updates sequencing,
     * buffers in-order frames, and forwards them to other eligible clients.
     *
     * @param data the raw UDP packet bytes
     * @param srcAddr the source IP address the packet came from
     * @param srcPort the source UDP port the packet came from
     * @throws Exception if deserialization or forwarding fails
     */
    public void processPacket(byte[] data, InetAddress srcAddr, int srcPort) throws Exception {
        System.out.println("[PROCESS] - Enter processPacket: src=" + srcAddr + ":" + srcPort + " size=" + (data==null?0:data.length));
        // Deserialize the audio packet
        AudioPacket audioPacket;
        try {
            audioPacket = deserializeAudioPacket(data);
            System.out.println("[PROCESS] - Deserialized packet: clientId=" + audioPacket.clientId + " seq=" + audioPacket.sequenceNumber + " audioLen=" + (audioPacket.audioData==null?0:audioPacket.audioData.length));
        } catch (Exception e) {
            System.err.println("[PROCESS] - Failed to deserialize packet from " + srcAddr + ":" + srcPort + " -> " + e);
            return;
        }

        // Get existing client state; do not auto-create because REGISTER should create it
        ClientState state = clientStates.get(audioPacket.clientId);
        if (state == null) {
            System.out.println("[PROCESS] - Dropping packet for unknown clientId=" + audioPacket.clientId + " from " + srcAddr + ":" + srcPort + " size=" + (data==null?0:data.length));
            return;
        }

        synchronized (state) {
            // Verify client status: only forward packets from ACTIVE clients.
            if (state.status != ClientStatus.ACTIVE) {
                // Do not forward audio while muted/left/disconnected, but update sequencing so future
                // packets from this client don't get permanently blocked due to a missing expectedSeq.
                System.out.println("[PROCESS] - Received packet from clientId=" + audioPacket.clientId + " but status=" + state.status + " (not forwarding)");
                // update lastHeard for presence
                state.lastHeard = System.currentTimeMillis();

                // If the incoming sequence number is at or ahead of expectedSeq, advance expectedSeq
                // to avoid blocking when the client resumes sending in-order frames.
                if (audioPacket.sequenceNumber >= state.expectedSeq) {
                    // set expectedSeq to one past this received packet so later packets can be emitted normally
                    state.expectedSeq = audioPacket.sequenceNumber + 1;
                }
                // Do not buffer or forward this packet.
                return;
            }

            // Verify source address/port. Option B: accept same IP and update port if different
            if (state.clientAddress == null) {
                // first time seeing UDP packets from this client: set address/port
                state.clientAddress = srcAddr;
                state.clientPort = srcPort;
            } else {
                if (!addressesMatch(state.clientAddress, srcAddr)) {
                    System.out.println("[PROCESS] - Dropping packet: src address " + srcAddr + " doesn't match registered " + state.clientAddress);
                    return;
                }
                if (state.clientPort != srcPort) {
                    System.out.println("[PROCESS] - Updating clientPort for clientId=" + state.clientId + " from " + state.clientPort + " to " + srcPort);
                    state.clientPort = srcPort; // allow port change (NAT rebinding)
                }
            }

            // Update last seen info and id
            state.lastHeard = System.currentTimeMillis();
            state.clientId = audioPacket.clientId;

            // Add packet to buffer with cap eviction (drop oldest)
            System.out.println("[PROCESS] - Buffering packet seq=" + audioPacket.sequenceNumber + " for client=" + audioPacket.clientId);
            state.buffer.put(audioPacket.sequenceNumber, audioPacket);
            while (state.buffer.size() > MAX_BUFFERED_PACKETS) {
                Integer firstKey = state.buffer.firstKey();
                state.buffer.remove(firstKey);
                System.out.println("[PROCESS] - Evicted oldest buffered packet seq=" + firstKey + " for client=" + state.clientId);
            }

            // Process in-order packets starting from expectedSeq
            List<AudioPacket> toSend = new ArrayList<>();
                while (state.buffer.containsKey(state.expectedSeq)) {
                AudioPacket next = state.buffer.remove(state.expectedSeq);
                System.out.println("[PROCESS] - Emitting in-order packet seq=" + next.sequenceNumber + " for client=" + next.clientId);
                toSend.add(next);
                state.expectedSeq++;
            }

            // Forward collected packets to other clients
            if (!toSend.isEmpty()) {
                System.out.println("[PROCESS] - Forwarding " + toSend.size() + " packets from client=" + audioPacket.clientId);
                for (ClientState clientState : clientStates.values()) { // iterate over clients
                    if (clientState.clientId == audioPacket.clientId) continue; // skip sender
                    synchronized (clientState) {
                        // Allow MUTED clients to continue receiving audio from others. Only skip clients
                        // that have left or are disconnected.
                        if (clientState.status == ClientStatus.LEFT || clientState.status == ClientStatus.DISCONNECTED) {
                            System.out.println("[PROCESS] - Skipping clientId=" + clientState.clientId + " because status=" + clientState.status);
                            continue;
                        }
                        if (clientState.clientAddress == null) {
                            System.out.println("[PROCESS] - Skipping clientId=" + clientState.clientId + " because address is null");
                            continue;
                        }
                        if (clientState.clientPort <= 0) {
                            System.out.println("[PROCESS] - Skipping clientId=" + clientState.clientId + " because port is invalid: " + clientState.clientPort);
                            continue;
                        }

                        for (AudioPacket pkt : toSend) {
                            try {
                                byte[] serialized = serializeAudioPacket(pkt);
                                DatagramPacket outPacket = new DatagramPacket(
                                    serialized,
                                    serialized.length,
                                    clientState.clientAddress,
                                    clientState.clientPort
                                );
                                socket.send(outPacket);
                                System.out.println("[PROCESS] - Sent packet seq=" + pkt.sequenceNumber + " to " + clientState.clientAddress + ":" + clientState.clientPort);
                            } catch (IOException e) {
                                System.err.println("[PROCESS] - Failed to send packet seq=" + pkt.sequenceNumber + " to clientId=" + clientState.clientId + " -> " + e);
                            }
                        }
                    }
                }
            }
        }
    }


    // 4 byte client ID, 4 bytes sequence number, 2 bytes audio data length, then audio bytes
    /**
     * Deserializes an audio packet from the custom 10-byte header plus payload.
     * <p>
     * Header layout (big-endian):
     * <pre>
     * 4 bytes clientId, 4 bytes sequenceNumber, 2 bytes audioDataLength, followed by audioData
     * </pre>
     * </p>
     *
     * @param data the raw UDP packet bytes
     * @return a parsed {@link AudioPacket}
     * @throws IllegalArgumentException if the packet is malformed
     */
    AudioPacket deserializeAudioPacket(byte[] data) {
        final int HEADER_SIZE = 10; // 4 (clientId) + 4 (seq) + 2 (len)
        if (data == null || data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Packet too short for header");
        }

        int clientId = ((data[0] & 0xFF) << 24)
                     | ((data[1] & 0xFF) << 16)
                     | ((data[2] & 0xFF) << 8)
                     | (data[3] & 0xFF);

        int sequenceNumber = ((data[4] & 0xFF) << 24)
                        |    ((data[5] & 0xFF) << 16)
                        |    ((data[6] & 0xFF) << 8)
                        |    (data[7] & 0xFF);

        int audioDataLength = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);

        if (audioDataLength < 0 || audioDataLength > data.length - HEADER_SIZE) {
            throw new IllegalArgumentException("Audio data length exceeds packet size");
        }

        byte[] audioData = Arrays.copyOfRange(data, HEADER_SIZE, HEADER_SIZE + audioDataLength);
        return new AudioPacket(clientId, sequenceNumber, audioData);
    }

    /**
     * Serializes an {@link AudioPacket} into the custom header + payload format.
     *
     * @param pkt the packet to serialize
     * @return bytes suitable for sending via UDP
     */
    byte[] serializeAudioPacket(AudioPacket pkt) {
        final int HEADER_SIZE = 10;
        byte[] data = new byte[HEADER_SIZE + pkt.audioData.length];
        data[0] = (byte) (pkt.clientId >> 24);
        data[1] = (byte) (pkt.clientId >> 16);
        data[2] = (byte) (pkt.clientId >> 8);
        data[3] = (byte) pkt.clientId;

        data[4] = (byte) (pkt.sequenceNumber >> 24);
        data[5] = (byte) (pkt.sequenceNumber >> 16);
        data[6] = (byte) (pkt.sequenceNumber >> 8);
        data[7] = (byte) pkt.sequenceNumber;

        data[8] = (byte) (pkt.audioData.length >> 8);
        data[9] = (byte) pkt.audioData.length;

        System.arraycopy(pkt.audioData, 0, data, HEADER_SIZE, pkt.audioData.length);
        return data;
    }

    // Compare addresses with a small tolerance for IPv4/IPv6 loopback differences.
    /**
     * Compares two IP addresses with tolerance for loopback variants.
     * Treats IPv4 127.0.0.1 and IPv6 ::1 as equivalent; otherwise requires exact match.
     *
     * @param a first address
     * @param b second address
     * @return true if considered equivalent, otherwise false
     */
    private boolean addressesMatch(InetAddress a, InetAddress b) {
        if (a == null || b == null) return false;
        // Treat IPv4 loopback 127.0.0.1 and IPv6 loopback ::1 as equivalent
        if (a.isLoopbackAddress() && b.isLoopbackAddress()) {
            String ha = a.getHostAddress();
            String hb = b.getHostAddress();
            // only treat the canonical IPv4 loopback and IPv6 loopback as equivalent
            if (("::1".equals(ha) && hb.startsWith("127.")) || ("::1".equals(hb) && ha.startsWith("127."))) {
                return true;
            }
            // otherwise fall through to exact match (so 127.0.0.1 != 127.0.0.2)
        }
        return a.equals(b);
    }

}


/**
 * Per-client state tracked by the server for presence and forwarding decisions.
 */
class ClientState {
    int clientId; 
    int clientPort; 
    InetAddress clientAddress; 
    String username;
    int expectedSeq = 0; 
    ClientStatus status = ClientStatus.ACTIVE; // could be ACTIVE, MUTED, LEFT, DISCONNECTED
    NavigableMap<Integer, AudioPacket> buffer = new TreeMap<>(); 
        // holds packets keyed by sequence number
    long lastHeard; // timestamp of last packet from this client
}


/**
 * Simple audio packet model used internally by the server for buffering/forwarding.
 */
class AudioPacket {
    public int clientId;
    public int sequenceNumber;
    public byte[] audioData;
    public long timestamp;

    /**
     * Constructs an audio packet instance.
     *
     * @param clientId the sender client ID
     * @param sequenceNumber the sequence number of the audio frame
     * @param audioData the audio payload bytes
     */
    public AudioPacket(int clientId, int sequenceNumber, byte[] audioData) {
        this.clientId = clientId;
        this.sequenceNumber = sequenceNumber;
        this.audioData = audioData;
        this.timestamp = System.currentTimeMillis();
    }
}

/**
 * Enumerates possible client states used by the server.
 */
enum ClientStatus {
    ACTIVE,
    MUTED,
    LEFT,
    DISCONNECTED
}
