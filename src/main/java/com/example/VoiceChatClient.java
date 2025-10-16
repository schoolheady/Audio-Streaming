package com.example;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.LineUnavailableException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VoiceChatClient {
    private static final Logger logger = Logger.getLogger(VoiceChatClient.class.getName());

    // instance-configurable server connection info (defaults kept for convenience)
    private final String serverIp;
    private final int tcpPort;
    private final int serverUdpPort;

    private TcpControlChannel tcpChannel;
    private AudioHandler audioHandler;
    private InetAddress serverAddr;
    private DatagramSocket udpSocket;
    private int localUdpPort = -1;
    private Thread monitorThread;

    private final AtomicBoolean isMute = new AtomicBoolean(false);

    public VoiceChatClient() {
        this("127.0.0.1", 4444, 5555);
    }

    public VoiceChatClient(String serverIp, int tcpPort, int serverUdpPort) {
        this.serverIp = serverIp;
        this.tcpPort = tcpPort;
        this.serverUdpPort = serverUdpPort;
        try {
            // Prepare control channel (clientId will be assigned by server)
            tcpChannel = new TcpControlChannel(this.serverIp, this.tcpPort, -1);
            serverAddr = InetAddress.getByName(this.serverIp);
            // Don't create AudioHandler yet; we'll create it after REGISTER completes and we have assigned id
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, "Initialization Error: " + e.getMessage(), e);
        }
        // Ensure we cleanup if the JVM exits unexpectedly
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                leaveSession();
            } catch (Exception ignored) {}
        }, "voicechat-shutdown-hook"));
    }

    public void joinSession() {
        try {
            // 1. Create UDP socket bound to any free port
            udpSocket = new DatagramSocket(0);
            localUdpPort = udpSocket.getLocalPort();

            if (!tcpChannel.connect()) {
                logger.severe("Cannot connect to TCP server.");
                udpSocket.close();
                return;
            }

            logger.info("Connected to TCP. Sending REGISTER command....");

            // 2. Send REGISTER and wait synchronously for assigned id
            int assignedId = tcpChannel.registerAndWait(localUdpPort, 3000);
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

            // Start a monitor thread that detects TCP disconnects and attempts reconnect/re-register
            startMonitor();
        } catch (SocketException se) {
            logger.log(Level.SEVERE, "Socket error during joinSession: " + se.getMessage(), se);
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
        if (tcpChannel.isConnected()) {
            logger.info("Sending LEAVE command...");
            tcpChannel.sendCommand("LEAVE"); 
            tcpChannel.disconnect();
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        if (audioHandler != null) {
            audioHandler.stopStreaming();
            audioHandler.stopReceiving();
        }
        logger.info("Left the session.");
    }

    public void toggleMute() {
        boolean mute = !isMute.get();
        isMute.set(mute);
        String command = mute ? "MUTE" : "UNMUTE"; 
        
        if (tcpChannel.isConnected()) {
            tcpChannel.sendCommand(command);
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