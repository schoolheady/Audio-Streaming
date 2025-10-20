package com.audiostreaming;

import javax.swing.SwingUtilities;
import java.net.DatagramSocket;
import java.net.ServerSocket;

/**
 * Main application entry point for the Audio Streaming server/client.
 * Supports three modes: server, client, and local (combined server+client).
 */
public class App {
    /**
     * Application entry point.
     * 
     * @param args Command line arguments. First argument specifies mode: "server", "client", or "local"
     * @throws Exception if server/client initialization fails
     */
    public static void main(String[] args) throws Exception {
        String mode = (args != null && args.length > 0) ? args[0].trim().toLowerCase() : "server";

        switch (mode) {
            case "server":
                {
                    DatagramSocket serverSocket = new DatagramSocket(5555);
                    Server server = new Server(serverSocket);
                    server.startTCPServer();
                    
                    Thread udpThread = new Thread(() -> {
                        try {
                            server.udpReceive();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, "server-udp-thread");
                    udpThread.setDaemon(true);
                    udpThread.start();
                    System.out.println("[MAIN] - Server running on UDP port 5555");

                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            server.stop();
                        } catch (Exception ignored) {
                        }
                    }));
                    
                    synchronized (App.class) {
                        App.class.wait();
                    }
                    break;
                }
            case "client":
                SwingUtilities.invokeLater(() -> new AudioStreamingUI().showUI());
                break;
            case "local":
            {
                DatagramSocket serverSocket = new DatagramSocket(0);
                int serverUdpPort = serverSocket.getLocalPort();
                Server server = new Server(serverSocket);
                try {
                    ServerSocket tcpSocket = new ServerSocket(0);
                    int serverTcpPort = tcpSocket.getLocalPort();
                    System.out.println("[MAIN] - Starting local server TCP on port " + serverTcpPort + " and UDP on port " + serverUdpPort);
                    server.startTCPServer(tcpSocket);
                    
                    VoiceChatClient client = new VoiceChatClient("127.0.0.1", serverTcpPort, serverUdpPort);
                    client.joinSession();
                    
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        client.leaveSession();
                        server.stop();
                    }));
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                    server.stop();
                    return;
                }
            }
            default:
                System.out.println("Usage: java -jar <app.jar> [server|client|local] (default: client)");
                break;
        }
    }
}
