package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;

public class AudioForwardingTest {

    @Test
    public void twoClients_forwardedAudioReceived() throws Exception {
        // Start server with ephemeral UDP port
        try (DatagramSocket serverUdp = new DatagramSocket(0)) {
            Server server = new Server(serverUdp);

            // Start TCP acceptor on ephemeral port
            try (ServerSocket tcpServerSocket = new ServerSocket(0)) {
                server.startTCPServer(tcpServerSocket);

                // Start UDP receive loop in background
                Thread udpThread = new Thread(() -> {
                    try {
                        server.udpReceive();
                    } catch (Exception e) {
                        // ignore in test shutdown
                    }
                }, "test-udp-receive");
                udpThread.setDaemon(true);
                udpThread.start();

                // Create two client UDP sockets
                try (DatagramSocket client1 = new DatagramSocket();
                     DatagramSocket client2 = new DatagramSocket()) {

                    // Create TCP control channels for both clients
                    TcpControlChannel ch1 = new TcpControlChannel("127.0.0.1", tcpServerSocket.getLocalPort(), -1);
                    TcpControlChannel ch2 = new TcpControlChannel("127.0.0.1", tcpServerSocket.getLocalPort(), -1);

                    assertTrue(ch1.connect(), "ch1 should connect to TCP server");
                    assertTrue(ch2.connect(), "ch2 should connect to TCP server");

                    int id1 = ch1.registerAndWait(client1.getLocalPort(), 2000);
                    int id2 = ch2.registerAndWait(client2.getLocalPort(), 2000);

                    assertTrue(id1 > 0, "client1 should get assigned id");
                    assertTrue(id2 > 0, "client2 should get assigned id");
                    assertNotEquals(id1, id2, "clients should have different ids");

                    // Prepare a synthetic audio payload and send from client1 to server UDP
                    byte[] audioPayload = new byte[100];
                    for (int i = 0; i < audioPayload.length; i++) audioPayload[i] = (byte) i;

                    // Build packet using server helper so header format matches
                    AudioPacket pkt = new AudioPacket(id1, 0, audioPayload);
                    byte[] serialized = server.serializeAudioPacket(pkt);

                    DatagramPacket send = new DatagramPacket(serialized, serialized.length, InetAddress.getByName("127.0.0.1"), serverUdp.getLocalPort());
                    client1.send(send);

                    // Wait for forwarded packet on client2
                    client2.setSoTimeout(2000);
                    DatagramPacket received = new DatagramPacket(new byte[1500], 1500);

                    // Receive should complete within timeout
                    client2.receive(received);

                    // Deserialize and verify
                    AudioPacket receivedPkt = server.deserializeAudioPacket(java.util.Arrays.copyOf(received.getData(), received.getLength()));
                    assertEquals(id1, receivedPkt.clientId, "Forwarded packet should carry sender id");
                    assertArrayEquals(audioPayload, receivedPkt.audioData, "Payload must match sent data");

                    // Clean up control channels
                    ch1.sendCommand("LEAVE");
                    ch2.sendCommand("LEAVE");
                    ch1.disconnect();
                    ch2.disconnect();
                }
            } finally {
                server.stop();
            }
        }
    }
}
