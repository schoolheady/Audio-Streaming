package com.audiostreaming;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;


public class Main {

    public static void main(String[] args) {
        String mode = null;
        if (args.length > 0) mode = args[0].trim().toLowerCase();

        if (mode == null || (!mode.equals("server") && !mode.equals("client"))) {
            // Interactive prompt if no or wrong args
            System.out.println("Usage: java com.audiostreaming.Main <server|client>");
            System.out.println("No valid mode supplied. Choose mode:\n  1) server\n  2) client\n  Press 1 or 2 (or Ctrl+C to cancel):");
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                String line = br.readLine();
                if (line == null) return;
                line = line.trim();
                if (line.equals("1")) mode = "server";
                else if (line.equals("2")) mode = "client";
                else {
                    System.out.println("No valid selection. Exiting.");
                    return;
                }
            } catch (Exception e) {
                System.err.println("Error reading input: " + e.getMessage());
                return;
            }
        }

        switch (mode) {
            case "server" -> startServer();
            case "client" -> startClient();
            default -> System.out.println("Unknown mode: " + mode);
        }
    }

    private static void startServer() {
        System.out.println("[MAIN] Starting server...");
        Server server;
        try {
            server = new Server();
        } catch (Exception e) {
            System.err.println("[MAIN] Failed to create server: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Start TCP and UDP loops in background (Server.startTCPServer/startUDPServer spawn threads)
        try {
            server.startTCPServer();
            server.startUDPServer();
        } catch (Exception e) {
            System.err.println("[MAIN] Failed to start server sockets: " + e.getMessage());
            e.printStackTrace();
            try { server.stopServer(); } catch (Exception ignored) {}
            return;
        }

        // Add shutdown hook to stop server cleanly
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[MAIN] Shutdown requested, stopping server...");
            try { server.stopServer(); } catch (Exception ignored) {}
            System.out.println("[MAIN] Server stopped.");
        }));

        System.out.println("[MAIN] Server started. Press Ctrl+C to stop.");
        // Keep main thread alive while server threads run
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            // Interrupted — allow JVM to exit and shutdown hook to run
        }
    }

    private static void startClient() {
        System.out.println("[MAIN] Starting client UI...");
        // Launch Swing UI on EDT
        SwingUtilities.invokeLater(() -> {
            try {
                AudioStreamingUI ui = new AudioStreamingUI();
                ui.showUI();
            } catch (Throwable t) {
                System.err.println("[MAIN] Failed to start UI: " + t.getMessage());
                t.printStackTrace();
            }
        });
    }
}
