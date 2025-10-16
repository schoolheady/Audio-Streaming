package com.example;

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

    public Server(DatagramSocket socket) throws Exception{
        this.clientStates = new ConcurrentHashMap<>();
        this.socket = socket;
    }


    public void startTCPServer() throws Exception{
    logger.info("[SERVER] - Server started");
        new Thread(() -> {
            try {
                tcpConnection();
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


    public void tcpConnection() throws Exception{
    tcpServerSocket = new ServerSocket(4444);
    logger.info("[TCP] - Listening for TCP clients on port 4444");

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
                try {
                    if (parts.length != 2) {
                        writer.write("ERROR invalid register\n");
                        writer.flush();
                        try { tcpSocket.close(); } catch (IOException ignored) {}
                        return;
                    }
                    udpPort = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ex) {
                    writer.write("ERROR invalid register\n");
                    writer.flush();
                    try { tcpSocket.close(); } catch (IOException ignored) {}
                    return;
                }

                // assign server id 
                Integer clientId = nextClientId.getAndIncrement();

                // map tcp socket to client id
                tcpSocketToClientId.put(tcpSocket, clientId);

                // populate or update client state
                ClientState st = clientStates.computeIfAbsent(clientId, id -> new ClientState());
                st.clientId = clientId;
                st.clientAddress = tcpSocket.getInetAddress();
                st.clientPort = udpPort;
                st.lastHeard = System.currentTimeMillis();
                st.status = ClientStatus.ACTIVE;

                // Reply with the assigned client id so client knows it
                writer.write("OK " + clientId + "\n");
                writer.flush();
                logger.info("[TCP] - Registered clientId=" + clientId + " udpPort=" + udpPort + " from " + tcpSocket.getRemoteSocketAddress());

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
                        logger.fine("[TCP] - Received command from " + clientId + ": " + line);
                        try {
                            handleCommands(line.trim(), clientId);
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
                        st.status = ClientStatus.DISCONNECTED;
                    }
                }
                try { tcpSocket.close(); } catch (IOException ignored) {}
                tcpClients.remove(tcpSocket);
                System.out.println("[TCP] - ControlHandler exiting for " + tcpSocket.getRemoteSocketAddress());
            }
        }
    }

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
            }
            case "LEAVE" -> {
                synchronized (st) {
                    st.status = ClientStatus.LEFT;
                }
                // remove mapping to tcp socket (if any) and client state
                tcpSocketToClientId.values().removeIf(id -> id == clientId);
                clientStates.remove(clientId);
                System.out.println("[TCP] - Client " + clientId + " left and unregistered");
            }
            case "JOIN" -> {
                synchronized (st) {
                    st.status = ClientStatus.ACTIVE;
                }
                System.out.println("[TCP] - Client " + clientId + " joined (ACTIVE)");
            }
            default -> System.out.println("[SERVER] - Unknown command: " + command);
        }
    // handle server commands from clients (mute/leave/join handled above)

    }

    // helper methods removed — command handling is done in handleCommands()
    
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
            System.out.println("[PROCESS] - Dropping packet for unknown clientId=" + audioPacket.clientId);
            return;
        }

        synchronized (state) {
            // Verify client status: only accept packets from ACTIVE clients (MUTED clients won't be forwarded)
            if (state.status != ClientStatus.ACTIVE) {
                System.out.println("[PROCESS] - Dropping packet from clientId=" + audioPacket.clientId + " due to status=" + state.status);
                // update lastHeard for presence if it's a packet (optional)
                state.lastHeard = System.currentTimeMillis();
                return;
            }

            // Verify source address/port. Option B: accept same IP and update port if different
            if (state.clientAddress == null) {
                // first time seeing UDP packets from this client: set address/port
                state.clientAddress = srcAddr;
                state.clientPort = srcPort;
            } else {
                if (!state.clientAddress.equals(srcAddr)) {
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
                        if (clientState.status != ClientStatus.ACTIVE) {
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

}


class ClientState {
    int clientId; 
    int clientPort; 
    InetAddress clientAddress; 
    int expectedSeq = 0; 
    ClientStatus status = ClientStatus.ACTIVE; // could be ACTIVE, MUTED, LEFT, DISCONNECTED
    NavigableMap<Integer, AudioPacket> buffer = new TreeMap<>(); 
        // holds packets keyed by sequence number
    long lastHeard; // timestamp of last packet from this client
}


class AudioPacket {
    public int clientId;
    public int sequenceNumber;
    public byte[] audioData;
    public long timestamp;

    public AudioPacket(int clientId, int sequenceNumber, byte[] audioData) {
        this.clientId = clientId;
        this.sequenceNumber = sequenceNumber;
        this.audioData = audioData;
        this.timestamp = System.currentTimeMillis();
    }
}

enum ClientStatus {
    ACTIVE,
    MUTED,
    LEFT,
    DISCONNECTED
}
