package com.audiostreaming;
import java.io.*;
import java.net.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the TCP control channel for communication with the server.
 * <p>
 * Handles registration, sending commands (MUTE, UNMUTE, JOIN, LEAVE),
 * and receiving server messages (OK, PRESENCE, etc.).
 * </p>
 */
public class TcpControlChannel {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final String serverIp;
    private final int port;
    private int clientId;
    private Thread listenerThread;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final BlockingQueue<Integer> registerQueue = new ArrayBlockingQueue<>(1);
    private final CopyOnWriteArrayList<Consumer<String>> serverListeners = new CopyOnWriteArrayList<>();

    /**
     * Constructs a TcpControlChannel with the specified server connection details.
     * 
     * @param serverIp the server IP address
     * @param port the TCP port number
     * @param clientId the initial client ID (-1 if not yet assigned)
     */
    public TcpControlChannel(String serverIp, int port, int clientId) {
        this.serverIp = serverIp;
        this.port = port;
        this.clientId = clientId;
    }

    /**
     * Returns the assigned client ID.
     * 
     * @return the client ID
     */
    public int getClientId() {
        return clientId;
    }

    /**
     * Sets a new client ID.
     * 
     * @param newId the new client ID
     */
    public void setClientId(int newId) {
        this.clientId = newId;
    }

    /**
     * Establishes a connection to the TCP server and starts the listener thread.
     * 
     * @return true if connection is successful, false otherwise
     */
    public boolean connect() {
        if (isConnected.get() && socket != null && !socket.isClosed()) return true;
        try {
            socket = new Socket(serverIp, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            isConnected.set(true);

            listenerThread = new Thread(this::tcpListener, "TCP-Listener");
            listenerThread.start();

            System.out.println("Connected to TCP server at " + serverIp + ":" + port);
            return true;

        } catch (IOException e) {
            System.err.println("Could not connect to TCP server: " + e.getMessage());
            isConnected.set(false);
            return false;
        }
    }

    /**
     * Thread for listening to incoming messages from the TCP server.
     */
    private void tcpListener() {
        try {
            String line;
            while (isConnected.get() && (line = in.readLine()) != null) {
                System.out.println("TCP received: " + line);

                // Process response from server
                if (line.startsWith("OK")) {
                    // Update clientId from server if available
                    String[] parts = line.split(" ");
                    if (parts.length > 1) {
                        try {
                            int id = Integer.parseInt(parts[1]);
                            setClientId(id);
                            System.out.println("Customer ID updated to: " + clientId);
                            // offer id to any waiting registerAndWait callers (non-blocking)
                            registerQueue.offer(id);
                        } catch (NumberFormatException ignored) { /* Ignore if no number */ }
                    }
                } else if (line.startsWith("ERROR")) {
                    System.err.println("Server Error: " + line.substring(6));
                    // Optional: call disconnect() if fatal error
                } else if (line.equals("HEARTBEAT")) {
                    // Ignore or log HEARTBEAT
                }
                // Notify listeners about server lines (PRESENCE ADD/REMOVE etc.)
                for (Consumer<String> c : serverListeners) {
                    try { c.accept(line); } catch (Exception ignored) {}
                }
                // Can process additional commands from server
            }
        } catch (IOException e) {
            if (isConnected.get()) {
                System.err.println("TCP listener error: " + e.getMessage());
            }
        } finally {
            // When stream is terminated, mark as disconnected
            isConnected.set(false);
            disconnect();
        }
    }

    public void addServerListener(Consumer<String> listener) {
        if (listener == null) return;
        // avoid duplicate registrations
        if (!serverListeners.contains(listener)) serverListeners.add(listener);
    }

    public void removeServerListener(Consumer<String> listener) {
        if (listener == null) return;
        // remove any occurrences to ensure complete cleanup
        while (serverListeners.remove(listener)) {
            // loop until removed all copies
        }
    }

    /**
     * Sends the REGISTER command to the server.
     * @param udpPort The UDP port to register.
     * @return true if command is sent, false otherwise.
     */
    public boolean register(int udpPort) {
        // Corrected condition: check if we can send a command
        if (out != null && isConnected.get()) {
            // REGISTER command <udpPort>\n
            String command = "REGISTER " + udpPort; // Added space for standard protocol format
            out.println(command);
            System.out.println("Sent TCP command: " + command);
            // Actually need to wait for OK/ERROR response here or in listen
            return true;
        }
        return false;
    }

    /**
     * Send REGISTER and block until the server replies with OK &lt;id&gt; or timeout.
     * Returns assigned id or -1 on timeout/failure.
     */
    public int registerAndWait(int udpPort, long timeoutMs) {
        if (out == null || !isConnected.get()) return -1;
        // clear any stale queued ids before sending a new REGISTER
        registerQueue.clear();
        return registerAndWait(udpPort, null, timeoutMs);
    }

    public int registerAndWait(int udpPort, String username, long timeoutMs) {
        if (out == null || !isConnected.get()) return -1;
        // clear any stale queued ids before sending a new REGISTER
        registerQueue.clear();
        String uname = (username == null) ? "" : username.replaceAll("\\s+", "_");
        String cmd = "REGISTER " + udpPort + (uname.isEmpty() ? "" : " " + uname);
        out.println(cmd);
        System.out.println("Sent TCP command: " + cmd + " (waiting for OK)");
        try {
            Integer id = registerQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return id == null ? -1 : id;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Sends a generic command to the server.
     * @param command The command string (e.g., MUTE, JOIN, LEAVE).
     */
    public void sendCommand(String command) {
        if (out != null && isConnected.get()) {
            // Other commands (MUTE, JOIN, LEAVE) only require command + \n
            out.println(command);
            System.out.println("TCP command sent: " + command);
        }
    }

    /**
     * Closes the TCP connection and cleans up resources.
     */
    public void disconnect() {
        if (isConnected.getAndSet(false)) {
            try {
                // Try to notify server we are leaving so it can clean up quickly
                try {
                    if (out != null && socket != null && !socket.isClosed()) {
                        // Send LEAVE to allow server to unregister the client immediately
                        out.println("LEAVE");
                        out.flush();
                    }
                } catch (Exception ignored) {}

                if (socket != null) {
                    socket.close();
                }
                if (in != null) { // Corrected from 'print' to 'in'
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing TCP connection: " + e.getMessage());
            }
        }
    }

    /**
     * Checks the current connection status.
     * @return true if the channel is connected and the socket is open.
     */
    public boolean isConnected() {
        return isConnected.get() && socket != null && !socket.isClosed();
    }
}