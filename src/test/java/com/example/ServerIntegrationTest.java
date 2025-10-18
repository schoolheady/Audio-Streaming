package com.example;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

public class ServerIntegrationTest {

    @Test
    public void testSerializeDeserializeRoundtrip() throws Exception {
        byte[] audio = new byte[320];
        AudioPacket pkt = new AudioPacket(5, 42, audio);
        Server dummyServer = new Server(new DatagramSocket());
        byte[] serialized = dummyServer.serializeAudioPacket(pkt);
        AudioPacket parsed = dummyServer.deserializeAudioPacket(serialized);
        assertEquals(pkt.clientId, parsed.clientId);
        assertEquals(pkt.sequenceNumber, parsed.sequenceNumber);
        assertEquals(pkt.audioData.length, parsed.audioData.length);
    }

    @Test
    public void testTcpRegisterReturnsAssignedId() throws Exception {
        Server server = new Server(new DatagramSocket());
        server.startTCPServer();
        Thread.sleep(300); // allow bind

    server.startTCPServer();
    Thread.sleep(200);
    try (Socket tcp = new Socket("127.0.0.1", server.getTcpPort())) {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(tcp.getOutputStream()));
            BufferedReader r = new BufferedReader(new InputStreamReader(tcp.getInputStream()));
            w.write("REGISTER 12345\n");
            w.flush();
            String reply = r.readLine();
            assertNotNull(reply);
            assertTrue(reply.startsWith("OK "));
            String[] parts = reply.split("\\s+");
            assertTrue(parts.length >= 2);
            Integer.parseInt(parts[1]); // should parse
        }

        server.stop();
    }

    @Test
    public void testProcessPacketForwardsToOtherClients() throws Exception {
        // Recording socket to capture server sends
        final ArrayBlockingQueue<DatagramPacket> sentQueue = new ArrayBlockingQueue<>(8);
        class RecSocket extends DatagramSocket {
            RecSocket() throws SocketException { super(); }
            @Override
            public void send(DatagramPacket p) {
                byte[] buf = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), buf, 0, p.getLength());
                sentQueue.offer(new DatagramPacket(buf, buf.length, p.getAddress(), p.getPort()));
            }
        }

        Server server = new Server(new RecSocket());

        // create two client states and inject into server.clientStates using reflection
        ClientState s1 = new ClientState();
        s1.clientId = 10;
        s1.clientAddress = InetAddress.getByName("127.0.0.1");
        s1.clientPort = 20001;

        ClientState s2 = new ClientState();
        s2.clientId = 11;
        s2.clientAddress = InetAddress.getByName("127.0.0.1");
        s2.clientPort = 20002;

        // inject
        Field f = Server.class.getDeclaredField("clientStates");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Integer, ClientState> map = (ConcurrentHashMap<Integer, ClientState>) f.get(server);
        map.put(10, s1);
        map.put(11, s2);

        // create an audio packet from s1 and process
        AudioPacket pkt = new AudioPacket(10, 0, new byte[320]);
        byte[] serialized = server.serializeAudioPacket(pkt);
        server.processPacket(serialized, s1.clientAddress, s1.clientPort);

        DatagramPacket forwarded = sentQueue.poll(2, TimeUnit.SECONDS);
        assertNotNull(forwarded, "server should forward packet to other client");
        AudioPacket parsed = server.deserializeAudioPacket(forwarded.getData());
        assertEquals(10, parsed.clientId);
    }
}
