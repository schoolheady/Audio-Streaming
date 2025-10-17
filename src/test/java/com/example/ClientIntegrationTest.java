package com.example;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ClientIntegrationTest {

    @Test
    public void registerAndReceiveAssignedId() throws Exception {
    DatagramSocket serverUdp = new DatagramSocket(0);
    int serverUdpPort = serverUdp.getLocalPort();
    Server server = new Server(serverUdp);
        // start UDP receiver thread
        Thread udpThread = new Thread(() -> { try { server.udpReceive(); } catch (Exception ignored) {} });
        udpThread.setDaemon(true);
        udpThread.start();
        server.startTCPServer();
        Thread.sleep(200);

        TcpControlChannel ctrl = new TcpControlChannel("127.0.0.1", 4444, -1);
        assertTrue(ctrl.connect());

        try (DatagramSocket clientUdp = new DatagramSocket(0)) {
            int localPort = clientUdp.getLocalPort();
            int assigned = ctrl.registerAndWait(localPort, 2000);
            assertTrue(assigned > 0, "server should assign a positive id");
        } finally {
            ctrl.disconnect();
            server.stop();
            serverUdp.close();
            Thread.sleep(50);
        }
    }

    @Test
    public void clientRegisterAndUdpForwarding() throws Exception {
    DatagramSocket serverUdp = new DatagramSocket(0);
    int serverUdpPort = serverUdp.getLocalPort();
    Server server = new Server(serverUdp);
    Thread udpThread = new Thread(() -> { try { server.udpReceive(); } catch (Exception ignored) {} });
        udpThread.setDaemon(true);
        udpThread.start();
        server.startTCPServer();
        Thread.sleep(200);

    // register client1
    TcpControlChannel c1 = new TcpControlChannel("127.0.0.1", server.getTcpPort(), -1);
        assertTrue(c1.connect());
        DatagramSocket u1 = new DatagramSocket(0);
        int p1 = u1.getLocalPort();
        int id1 = c1.registerAndWait(p1, 2000);
    assertTrue(id1 > 0);
    Thread.sleep(50);

    // register client2
    TcpControlChannel c2 = new TcpControlChannel("127.0.0.1", server.getTcpPort(), -1);
        assertTrue(c2.connect());
        DatagramSocket u2 = new DatagramSocket(0);
        int p2 = u2.getLocalPort();
        int id2 = c2.registerAndWait(p2, 2000);
    assertTrue(id2 > 0);
    Thread.sleep(50);

        // Sanity-check server's known client states before sending UDP
        java.lang.reflect.Field f = Server.class.getDeclaredField("clientStates");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<Integer, ClientState> map = (java.util.concurrent.ConcurrentHashMap<Integer, ClientState>) f.get(server);
        assertTrue(map.containsKey(id1), "server should have state for client1");
        assertTrue(map.containsKey(id2), "server should have state for client2");
        ClientState cs1 = map.get(id1);
        ClientState cs2 = map.get(id2);
        assertNotNull(cs1.clientAddress, "client1 address should be set in server state");
        assertNotNull(cs2.clientAddress, "client2 address should be set in server state");
        assertEquals(p1, cs1.clientPort, "client1 port should match registered UDP port");
        assertEquals(p2, cs2.clientPort, "client2 port should match registered UDP port");
    assertNotEquals(id1, id2);

        CountDownLatch latch = new CountDownLatch(1);
        final byte[][] received = new byte[1][];
        Thread listener = new Thread(() -> {
            try {
                byte[] buf = new byte[1500];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                u2.receive(p);
                byte[] copy = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), copy, 0, p.getLength());
                received[0] = copy;
                latch.countDown();
            } catch (Exception ignored) {}
        });
    listener.setDaemon(true);
    listener.start();

        // ensure listener thread is scheduled and server has had time to register client endpoints
        Thread.sleep(200);
        // send a packet from client1 to server
        AudioPacket pkt = new AudioPacket(id1, 0, new byte[320]);
        byte[] data = server.serializeAudioPacket(pkt);
    DatagramPacket send = new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), serverUdpPort);
    u1.send(send);

    boolean arrived = latch.await(3, TimeUnit.SECONDS);
        assertTrue(arrived, "client2 should receive forwarded packet");

        AudioPacket parsed = server.deserializeAudioPacket(received[0]);
        assertEquals(id1, parsed.clientId);

        // cleanup
        c1.disconnect();
        c2.disconnect();
        u1.close();
        u2.close();
        server.stop();
        serverUdp.close();
        Thread.sleep(50);
    }
}
