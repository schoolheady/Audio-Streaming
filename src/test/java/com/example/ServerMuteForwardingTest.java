package com.example;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

public class ServerMuteForwardingTest {

    @Test
    public void testMutePreventsForwardingAndUnmuteResumes() throws Exception {
        // Capture outgoing packets from the server
        final ArrayBlockingQueue<java.net.DatagramPacket> sentQueue = new ArrayBlockingQueue<>(8);
        class RecSocket extends java.net.DatagramSocket {
            RecSocket() throws SocketException { super(); }
            @Override
            public void send(java.net.DatagramPacket p) {
                byte[] buf = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), buf, 0, p.getLength());
                sentQueue.offer(new java.net.DatagramPacket(buf, buf.length, p.getAddress(), p.getPort()));
            }
        }

        Server server = new Server(new RecSocket());

        // create two client states and inject into server.clientStates using reflection
        ClientState s1 = new ClientState();
        s1.clientId = 10;
        s1.clientAddress = InetAddress.getByName("127.0.0.1");
        s1.clientPort = 20001;
        s1.status = ClientStatus.ACTIVE;

        ClientState s2 = new ClientState();
        s2.clientId = 11;
        s2.clientAddress = InetAddress.getByName("127.0.0.1");
        s2.clientPort = 20002;
        s2.status = ClientStatus.ACTIVE;

        // inject
        Field f = Server.class.getDeclaredField("clientStates");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Integer, ClientState> map = (ConcurrentHashMap<Integer, ClientState>) f.get(server);
        map.put(10, s1);
        map.put(11, s2);

    // create an audio packet from s1 and process -> should forward
    // Note: server expectedSeq defaults to 0; use sequence numbers starting at 0
    AudioPacket pkt1 = new AudioPacket(10, 0, new byte[320]);
        byte[] serialized1 = server.serializeAudioPacket(pkt1);
        server.processPacket(serialized1, s1.clientAddress, s1.clientPort);

    java.net.DatagramPacket forwarded1 = sentQueue.poll();
        assertNotNull(forwarded1, "server should forward packet when client is ACTIVE");
        AudioPacket p1 = server.deserializeAudioPacket(forwarded1.getData());
        assertEquals(10, p1.clientId);

        // Now mute client 10
        s1.status = ClientStatus.MUTED;

    AudioPacket pkt2 = new AudioPacket(10, 1, new byte[320]);
        byte[] serialized2 = server.serializeAudioPacket(pkt2);
        server.processPacket(serialized2, s1.clientAddress, s1.clientPort);

        java.net.DatagramPacket forwarded2 = sentQueue.poll();
        assertNull(forwarded2, "server should not forward packets when client is MUTED");

    // Unmute client 10
        s1.status = ClientStatus.ACTIVE;

    AudioPacket pkt3 = new AudioPacket(10, 2, new byte[320]);
        byte[] serialized3 = server.serializeAudioPacket(pkt3);
        server.processPacket(serialized3, s1.clientAddress, s1.clientPort);

        java.net.DatagramPacket forwarded3 = sentQueue.poll();
        assertNotNull(forwarded3, "server should forward packets again after UNMUTE");
        AudioPacket p3 = server.deserializeAudioPacket(forwarded3.getData());
        assertEquals(10, p3.clientId);

        // Now mark client LEFT and ensure no forwarding
        s1.status = ClientStatus.LEFT;
        AudioPacket pkt4 = new AudioPacket(10, 4, new byte[320]);
        server.processPacket(server.serializeAudioPacket(pkt4), s1.clientAddress, s1.clientPort);
        java.net.DatagramPacket forwarded4 = sentQueue.poll();
        assertNull(forwarded4, "server should not forward packets when client LEFT");
    }
}
