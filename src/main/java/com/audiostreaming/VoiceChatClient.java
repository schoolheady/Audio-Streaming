package com.audiostreaming;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.LineUnavailableException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Voice chat client that manages TCP control channel and UDP audio streaming.
 * <p>
 * Handles connection to the server, registration, audio capture/playback,
 * and state management (mute, join/leave).
 * </p>
 */
public class VoiceChatClient {
    private static final Logger logger = Logger.getLogger(VoiceChatClient.class.getName());

    private final String serverIp;
    private final int tcpPort;
    private final int serverUdpPort;

    private TcpControlChannel tcpChannel;
    private AudioHandler audioHandler;
    private InetAddress serverAddr;
    private DatagramSocket udpSocket;
    private int localUdpPort = -1;
    private Thread monitorThread;
    private final java.util.concurrent.atomic.AtomicBoolean isShuttingDown = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final AtomicBoolean isMute = new AtomicBoolean(false);
    private String username = "Guest";

    private java.util.function.Consumer<String> serverMessageListener;

    /**
     * Removes a previously added server message listener.
     * 
     * @param listener the listener to remove
     */
    public void removeServerMessageListener(java.util.function.Consumer<String> listener) {
        if (listener == null) return;
        try {
            if (tcpChannel != null) tcpChannel.removeServerListener(listener);
        } catch (Exception ignored) {}
        if (this.serverMessageListener == listener) this.serverMessageListener = null;
    }

    /**
     * Returns the client ID assigned by the server.
     * 
     * @return the assigned client ID, or -1 if not assigned
     */
    public int getAssignedClientId() {
        if (tcpChannel != null) return tcpChannel.getClientId();
        return -1;
    }

    /**
     * Creates a VoiceChatClient with default connection parameters.
     */
    public VoiceChatClient() {
        this("127.0.0.1", 4444, 5555);
    }

    /**
     * Creates a VoiceChatClient with specified connection parameters.
     * 
     * @param serverIp the server IP address
     * @param tcpPort the TCP control port
     * @param serverUdpPort the UDP audio port
     */
    public VoiceChatClient(String serverIp, int tcpPort, int serverUdpPort) {
        this.serverIp = serverIp;
        this.tcpPort = tcpPort;
        this.serverUdpPort = serverUdpPort;
        try {
            tcpChannel = new TcpControlChannel(this.serverIp, this.tcpPort, -1);
            serverAddr = InetAddress.getByName(this.serverIp);
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, "Initialization Error: " + e.getMessage(), e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                leaveSession();
            } catch (Exception ignored) {}
        }, "voicechat-shutdown-hook"));
    }

    /**
     * Joins the voice chat session by registering with the server and starting audio.
     */
    public void joinSession() {
        try {
            udpSocket = new DatagramSocket(0);
            localUdpPort = udpSocket.getLocalPort();

            if (!tcpChannel.connect()) {
                logger.severe("Cannot connect to TCP server.");
                udpSocket.close();
                return;
            }

            logger.info("Connected to TCP. Preparing to send REGISTER command....");

            if (serverMessageListener != null) {
                tcpChannel.addServerListener(serverMessageListener);
            }

            int assignedId = tcpChannel.registerAndWait(localUdpPort, username, 3000);
            if (assignedId < 0) {
                logger.severe("Failed to register with server (no OK reply).");
                tcpChannel.disconnect();
                udpSocket.close();
                return;
            }

            logger.log(Level.INFO, "Registered with server, assigned clientId={0}", assignedId);

            // 3. Create AudioHandler with the pre-bound socket and assigned id
            try {
                audioHandler = new AudioHandler(serverAddr, serverUdpPort, udpSocket, assignedId, isMute);
            } catch (LineUnavailableException lue) {
                logger.log(Level.SEVERE, "Audio device unavailable: " + lue.getMessage(), lue);
                udpSocket.close();
                tcpChannel.disconnect();
                return;
            }

            // 4. Start streaming/receiving
            audioHandler.startStreaming();
            audioHandler.startReceiving();

            // 5. Send JOIN
            tcpChannel.sendCommand("JOIN");

            // hook up server listener if UI registered one
            if (serverMessageListener != null) {
                tcpChannel.addServerListener(serverMessageListener);
            }

            // Start a monitor thread that detects TCP disconnects and attempts reconnect/re-register
            startMonitor();
        } catch (SocketException se) {
            logger.log(Level.SEVERE, "Socket error during joinSession: " + se.getMessage(), se);
        }
    }

    /**
     * Ensure the TCP control channel is connected (without starting audio).
     * Returns true if connected or successfully connected, false otherwise.
     */
    public boolean connectControl() {
        try {
            return tcpChannel != null && tcpChannel.connect();
        } catch (Exception e) {
            return false;
        }
    }

    private void startMonitor() {
        if (monitorThread != null && monitorThread.isAlive()) return;
        monitorThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (tcpChannel == null || !tcpChannel.isConnected()) {
                        logger.info("TCP disconnected - stopping audio and attempting reconnect...");
                        if (audioHandler != null) {
                            audioHandler.stopStreaming();
                            audioHandler.stopReceiving();
                        }

                        // Attempt to reconnect in a loop
                        boolean reconnected = false;
                        for (int attempt = 0; attempt < 10 && !reconnected; attempt++) {
                            try {
                                Thread.sleep(1000L * (attempt + 1));
                            } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                            try {
                                if (tcpChannel.connect()) {
                                    int newId = tcpChannel.registerAndWait(localUdpPort, 3000);
                                    if (newId > 0) {
                                        tcpChannel.sendCommand("JOIN");
                                        if (audioHandler != null) {
                                            audioHandler.setAssignedClientId(newId);
                                            audioHandler.startStreaming();
                                            audioHandler.startReceiving();
                                        }
                                        logger.info("Reconnected and re-registered with id=" + newId);
                                        reconnected = true;
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                logger.log(Level.INFO, "Reconnect attempt failed: " + e.getMessage());
                            }
                        }
                        if (!reconnected) {
                            logger.info("Failed to reconnect after attempts; monitor will retry later.");
                            try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        }
                    }
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Monitor thread error: " + e.getMessage(), e);
                }
            }
        }, "voicechat-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public void leaveSession() {
        // Backwards-compatible full shutdown: leave call and disconnect from server.
        disconnect();
    }

    /**
     * Leave the audio call but keep the TCP control channel connected. This allows the
     * user to "Leave Call" and later "Join Call" quickly without reconnecting to TCP.
     */
    public void leaveCall() {
        // Send LEAVE to server; perform audio shutdown asynchronously so callers (UI) don't block.
        try {
            if (tcpChannel != null && tcpChannel.isConnected()) {
                tcpChannel.sendCommand("LEAVE");
            }
        } catch (Exception ignored) {}

        final AudioHandler handler = this.audioHandler;
        if (handler == null) {
            logger.info("No audio handler active when leaving call.");
            return;
        }

        // Stop audio on background thread to avoid blocking the caller (UI EDT)
        Thread stopThread = new Thread(() -> {
            try {
                handler.stopStreaming();
                handler.stopReceiving();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error shutting down audio handler: " + e.getMessage(), e);
            } finally {
                // clear reference so a subsequent join creates a fresh handler
                if (this.audioHandler == handler) this.audioHandler = null;
            }
        }, "voicechat-leave-audio-shutdown");
        stopThread.setDaemon(true);
        stopThread.start();
        logger.info("Leaving call: audio shutdown scheduled on background thread.");
    }

    /**
     * Fully disconnect from the server: send LEAVE and close TCP, stop audio and monitor.
     */
    public void disconnect() {
        // Make disconnect non-blocking to avoid blocking callers (e.g., UI EDT).
        if (!isShuttingDown.compareAndSet(false, true)) return;

        Thread shutdown = new Thread(() -> {
            try {
                // Remove any server listener first to avoid callbacks during teardown
                try {
                    if (serverMessageListener != null && tcpChannel != null) {
                        tcpChannel.removeServerListener(serverMessageListener);
                    }
                } catch (Exception ignored) {}

                if (tcpChannel != null && tcpChannel.isConnected()) {
                    logger.info("Sending LEAVE command and disconnecting TCP...");
                    try { tcpChannel.sendCommand("LEAVE"); } catch (Exception ignored) {}
                    try { tcpChannel.disconnect(); } catch (Exception ignored) {}
                }

                if (monitorThread != null) {
                    monitorThread.interrupt();
                    monitorThread = null;
                }
                if (audioHandler != null) {
                    audioHandler.stopStreaming();
                    audioHandler.stopReceiving();
                }
                try { if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close(); } catch (Exception ignored) {}
            } finally {
                isShuttingDown.set(false);
            }
            logger.info("Fully disconnected from server.");
        }, "voicechat-disconnect");
        shutdown.setDaemon(true);
        shutdown.start();
    }

    public void setUsername(String username) { this.username = username == null ? "Guest" : username; }

    public void addServerMessageListener(java.util.function.Consumer<String> listener) {
        this.serverMessageListener = listener;
        // If the control channel is already connected, register immediately so listeners
        // receive in-flight messages.
        try {
            if (listener != null && tcpChannel != null && tcpChannel.isConnected()) {
                tcpChannel.addServerListener(listener);
            }
        } catch (Exception ignored) {}
    }

    public void toggleMute() {
        boolean mute = !isMute.get();
        isMute.set(mute);
        String command = mute ? "MUTE" : "UNMUTE"; 
        // Try to send the command immediately. If the TCP control channel is down,
        // attempt a quick reconnect+register in the background so the server state
        // is updated (this avoids staying muted when the TCP connection dropped).
        if (tcpChannel.isConnected()) {
            tcpChannel.sendCommand(command);
        } else {
            // Non-blocking attempt to reconnect and send the command
            new Thread(() -> {
                try {
                    if (tcpChannel.connect()) {
                        // try to re-register using our local UDP port (if available)
                        int port = localUdpPort <= 0 ? -1 : localUdpPort;
                        if (port > 0) {
                            int newId = tcpChannel.registerAndWait(port, username, 2000);
                            if (newId > 0) {
                                tcpChannel.sendCommand(command);
                            }
                        } else {
                            // If we don't have a UDP port, still try to send command
                            tcpChannel.sendCommand(command);
                        }
                    }
                } catch (Exception ignored) {}
            }, "mute-reconnect-thread");
        }
        logger.log(Level.INFO, "Changed mute state to: {0}", mute ? "Muted" : "Unmuted");
    }

    public static void main(String[] args) {
        VoiceChatClient client = new VoiceChatClient();
        client.joinSession();

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String command;
            logger.info("Enter commands: join, leave, mute, unmute");
            while (client.tcpChannel.isConnected() && (command = in.readLine()) != null) {
                if (command.equalsIgnoreCase("leave")) {
                    client.leaveSession();
                    break;
                } else if (command.equalsIgnoreCase("mute")) {
                    if (!client.isMute.get()) {
                        client.toggleMute();
                    }
                } else if (command.equalsIgnoreCase("unmute")) {
                    if (client.isMute.get()) {
                        client.toggleMute();
                    }
                } else {
                    logger.log(Level.WARNING, "Unknown command: {0}", command);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "I/O error in main loop", e);
        } finally {
            client.leaveSession();
        }
    }
}