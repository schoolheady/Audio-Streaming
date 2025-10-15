package com.example;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class App {

    public static void main(String[] args) throws Exception {
        // Start server bound to port 5555
        DatagramSocket serverSocket = new DatagramSocket(5555);
        Server server = new Server(serverSocket);

        // Start server UDP listener in background
        new Thread(() -> {
            try {
                server.udpReceive(); // runs indefinitely
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "server-udp-thread").start();

        System.out.println("[MAIN] - Server running on UDP port 5555");

        InetAddress localhost = InetAddress.getByName("127.0.0.1");

        // Create two client sockets (each will have its own ephemeral port)
        DatagramSocket client1 = new DatagramSocket();
        DatagramSocket client2 = new DatagramSocket();

        System.out.println("[MAIN] - client1 bound to " + client1.getLocalAddress() + ":" + client1.getLocalPort());
        System.out.println("[MAIN] - client2 bound to " + client2.getLocalAddress() + ":" + client2.getLocalPort());

        // Start listeners for forwarded packets
        startClientListener(client1, "CLIENT1");
        startClientListener(client2, "CLIENT2");

        // Send interleaved packets from both clients to server
        for (int seq = 0; seq < 10; seq++) {
            byte[] audioData = new byte[320];

            AudioPacket p1 = new AudioPacket(1, seq, audioData);
            byte[] d1 = server.serializeAudioPacket(p1);
            client1.send(new DatagramPacket(d1, d1.length, localhost, 5555));
            System.out.println("[MAIN] - client1 sent seq=" + seq);

            Thread.sleep(15);

            AudioPacket p2 = new AudioPacket(2, seq, audioData);
            byte[] d2 = server.serializeAudioPacket(p2);
            client2.send(new DatagramPacket(d2, d2.length, localhost, 5555));
            System.out.println("[MAIN] - client2 sent seq=" + seq);

            Thread.sleep(20);
        }

        // Let threads run for a bit to observe forwarded packets
        Thread.sleep(2000);

    client1.close();
    client2.close();
    server.stop();
        System.out.println("[MAIN] - Done");
    }

    private static void startClientListener(DatagramSocket sock, String name) {
        new Thread(() -> {
            try {
                byte[] buf = new byte[1500];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (!sock.isClosed()) {
                    sock.receive(p);
                    System.out.println("[" + name + "] - Received forwarded packet length=" + p.getLength() + " from " + p.getAddress() + ":" + p.getPort());
                }
            } catch (Exception e) {
                if (!sock.isClosed()) e.printStackTrace();
            }
        }, name + "-listener").start();
    }
}
