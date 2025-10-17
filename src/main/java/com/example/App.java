package com.example;

import javax.swing.SwingUtilities;

import java.net.DatagramSocket;
import java.net.ServerSocket;

public class App {
    public static void main(String[] args) throws Exception {
        String mode = (args != null && args.length > 0) ? args[0].trim().toLowerCase() : "client";

        switch (mode) {
            case "server":
                {
                    // Start server bound to port 5555
                    DatagramSocket serverSocket = new DatagramSocket(5555);
                    Server server = new Server(serverSocket);
                    // Start TCP control acceptor and UDP listener in background
                    server.startTCPServer();
                    // Start server UDP listener in background
                    Thread udpThread = new Thread(() -> {
                        try {
                            server.udpReceive(); // runs indefinitely
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, "server-udp-thread");
                    udpThread.setDaemon(true);
                    udpThread.start();
                    System.out.println("[MAIN] - Server running on UDP port 5555");
                    // Keep main thread alive until interrupted
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            server.stop();
                        } catch (Exception ignored) {
                        }
                    }));    // Block main thread
                    synchronized (App.class) {
                        App.class.wait();
                    }       break;
                }
            case "client":
                // Launch the Swing UI which integrates with VoiceChatClient
                SwingUtilities.invokeLater(() -> new AudioStreamingUI().showUI());
                break;
            case "local":
            {
                // Start server in this process (both TCP and UDP) and then launch a client that connects to it.
                DatagramSocket serverSocket = new DatagramSocket(0);
                int serverUdpPort = serverSocket.getLocalPort();
                Server server = new Server(serverSocket);
                try {
                    // bind TCP to an ephemeral port to avoid collisions with any existing server
                    ServerSocket tcpSocket = new ServerSocket(0);
                    int serverTcpPort = tcpSocket.getLocalPort();
                    System.out.println("[MAIN] - Starting local server TCP on port " + serverTcpPort + " and UDP on port " + serverUdpPort);
                    server.startTCPServer(tcpSocket);
                    
                    // create client connected to the newly bound TCP port and server UDP port
                    VoiceChatClient client = new VoiceChatClient("127.0.0.1", serverTcpPort, serverUdpPort);
                    client.joinSession();
                    // add shutdown hook to stop server and client
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        client.leaveSession();
                        server.stop();
                    }));
                    return; // local mode runs the client UI and then exits when user leaves
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
