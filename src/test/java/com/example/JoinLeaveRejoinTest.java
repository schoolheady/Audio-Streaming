package com.example;

import org.junit.jupiter.api.Test;

import com.audiostreaming.Server;

import org.junit.jupiter.api.AfterEach;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that joining -> leaving -> rejoining doesn't create duplicate participant entries.
 */
public class JoinLeaveRejoinTest {
    private Server server;
    private DatagramSocket udpSocket;

    @AfterEach
    public void cleanup() throws Exception {
        if (server != null) server.stop();
        if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
    }

    @Test
    public void testJoinLeaveRejoinNoDuplicates() throws Exception {
        // 1. Start server
        udpSocket = new DatagramSocket(0);
        server = new Server(udpSocket);
        
        ServerSocket tcpServerSocket = new ServerSocket();
        tcpServerSocket.setReuseAddress(true);
        tcpServerSocket.bind(new InetSocketAddress(0));
        int tcpPort = tcpServerSocket.getLocalPort();
        
        server.startTCPServer(tcpServerSocket);
        
        // Allow server to start
        Thread.sleep(500);
        
        // 2. Client connects and registers
        Socket clientSocket = new Socket("127.0.0.1", tcpPort);
        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
        
        // Track all PRESENCE messages received
        List<String> presenceMessages = new CopyOnWriteArrayList<>();
        Thread listenerThread = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println("[CLIENT] Received: " + line);
                    if (line.startsWith("PRESENCE ADD") || line.startsWith("PRESENCE REMOVE")) {
                        presenceMessages.add(line);
                    }
                }
            } catch (IOException ignored) {}
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
        
        // 3. Send REGISTER and wait for OK
        out.println("REGISTER 12345 TestUser");
        Thread.sleep(200);
        
        // 4. Send JOIN command
        out.println("JOIN");
        Thread.sleep(200);
        
        // Verify: should have received PRESENCE ADD for our own registration
        long addCount = presenceMessages.stream()
            .filter(m -> m.contains("PRESENCE ADD") && m.contains("TestUser"))
            .count();
        assertEquals(1, addCount, "Should have exactly 1 PRESENCE ADD after initial join");
        
        // 5. Send LEAVE command
        out.println("LEAVE");
        Thread.sleep(200);
        
        // Verify: should have received PRESENCE REMOVE
        long removeCount = presenceMessages.stream()
            .filter(m -> m.contains("PRESENCE REMOVE"))
            .count();
        assertEquals(1, removeCount, "Should have exactly 1 PRESENCE REMOVE after leave");
        
        // 6. Clear recorded messages and send JOIN again (rejoining)
        presenceMessages.clear();
        out.println("JOIN");
        Thread.sleep(200);
        
        // Verify: should receive another PRESENCE ADD for rejoin
        long rejoinAddCount = presenceMessages.stream()
            .filter(m -> m.contains("PRESENCE ADD") && m.contains("TestUser"))
            .count();
        assertEquals(1, rejoinAddCount, "Should have exactly 1 PRESENCE ADD after rejoining");
        
        // 7. Verify no duplicate presence messages (server shouldn't broadcast extra PRESENCE ADD)
        long totalAddAfterRejoin = presenceMessages.stream()
            .filter(m -> m.contains("PRESENCE ADD"))
            .count();
        assertEquals(1, totalAddAfterRejoin, "Should have exactly 1 PRESENCE ADD message after rejoin (no duplicates)");
        
        clientSocket.close();
    }
}
