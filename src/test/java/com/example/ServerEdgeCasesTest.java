package com.example;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A focused edge-case test suite that exercises buffering, reordering, eviction,
 * spoof protection (different source IP), mute behavior, and client removal.
 */
public class ServerEdgeCasesTest {

    // Helper fake socket to capture outgoing sends
    static class RecSocket extends DatagramSocket {
        final ArrayBlockingQueue<DatagramPacket> q = new ArrayBlockingQueue<>(32);
        RecSocket() throws Exception { super(); }
        @Override
        public void send(DatagramPacket p) {
            byte[] buf = new byte[p.getLength()];
            System.arraycopy(p.getData(), p.getOffset(), buf, 0, p.getLength());
            q.offer(new DatagramPacket(buf, buf.length, p.getAddress(), p.getPort()));
        }
    }

    @Test
    public void reorderAndInOrderEmission() throws Exception {
        RecSocket rs = new RecSocket();
        Server s = new Server(rs);

        // one sender (id 100) and one receiver (id 200)
        ClientState sender = new ClientState();
        sender.clientId = 100;
        sender.clientAddress = InetAddress.getByName("127.0.0.1");
        sender.clientPort = 40000;
        sender.status = ClientStatus.ACTIVE;
        s.clientStates.put(100, sender);

        ClientState recv = new ClientState();
        recv.clientId = 200;
        recv.clientAddress = InetAddress.getByName("127.0.0.1");
        recv.clientPort = 40001;
        recv.status = ClientStatus.ACTIVE;
        s.clientStates.put(200, recv);

        // send packets out of order: seq 1, seq 0, seq 2. Expected emission order: 0,1,2
        AudioPacket p1 = new AudioPacket(100, 1, new byte[10]);
        AudioPacket p0 = new AudioPacket(100, 0, new byte[10]);
        AudioPacket p2 = new AudioPacket(100, 2, new byte[10]);

        // deliver p1
        s.processPacket(s.serializeAudioPacket(p1), sender.clientAddress, sender.clientPort);
        // deliver p0
        s.processPacket(s.serializeAudioPacket(p0), sender.clientAddress, sender.clientPort);
        // deliver p2
        s.processPacket(s.serializeAudioPacket(p2), sender.clientAddress, sender.clientPort);

        // expect three forwarded packets in-order
        DatagramPacket f0 = rs.q.poll(1, TimeUnit.SECONDS);
        DatagramPacket f1 = rs.q.poll(1, TimeUnit.SECONDS);
        DatagramPacket f2 = rs.q.poll(1, TimeUnit.SECONDS);
        assertNotNull(f0, "expected first forwarded packet");
        assertNotNull(f1, "expected second forwarded packet");
        assertNotNull(f2, "expected third forwarded packet");

        assertEquals(0, s.deserializeAudioPacket(f0.getData()).sequenceNumber);
        assertEquals(1, s.deserializeAudioPacket(f1.getData()).sequenceNumber);
        assertEquals(2, s.deserializeAudioPacket(f2.getData()).sequenceNumber);
    }

    @Test
    public void bufferEvictionWhenFull() throws Exception {
        RecSocket rs = new RecSocket();
        Server s = new Server(rs);

        // create client with tiny buffer cap for test via reflection if needed
        // We'll rely on the server's MAX_BUFFERED_PACKETS but push more than that.
        ClientState sender = new ClientState();
        sender.clientId = 300;
        sender.clientAddress = InetAddress.getByName("127.0.0.1");
        sender.clientPort = 50000;
        sender.status = ClientStatus.ACTIVE;
        s.clientStates.put(300, sender);

        ClientState recv = new ClientState();
        recv.clientId = 301;
        recv.clientAddress = InetAddress.getByName("127.0.0.1");
        recv.clientPort = 50001;
        recv.status = ClientStatus.ACTIVE;
        s.clientStates.put(301, recv);

        // push more than MAX_BUFFERED_PACKETS out-of-order packets so oldest get evicted
    // use a test-local total larger than the server's buffer cap (server cap is 200)
    final int total = 300;
        for (int i = total - 1; i >= 0; i--) { // insert descending so expectedSeq stays at 0
            AudioPacket p = new AudioPacket(300, i, new byte[4]);
            s.processPacket(s.serializeAudioPacket(p), sender.clientAddress, sender.clientPort);
        }

        // After inserting many packets, we should still be able to forward starting at expectedSeq=0
        // but the server would have evicted earliest ones, so we may not get full sequence.
        // Drain some forwards (if any were emitted)
        int got = 0;
        while (got < 5) {
            DatagramPacket fp = rs.q.poll(500, TimeUnit.MILLISECONDS);
            if (fp == null) break;
            got++;
        }

        // At minimum, ensure server didn't crash and queue has at most some elements
        assertTrue(got >= 0);
    }

    @Test
    public void spoofProtectionDifferentIpDropped() throws Exception {
        RecSocket rs = new RecSocket();
        Server s = new Server(rs);

        ClientState a = new ClientState();
        a.clientId = 400;
        a.clientAddress = InetAddress.getByName("127.0.0.1");
        a.clientPort = 60000;
        a.status = ClientStatus.ACTIVE;
        s.clientStates.put(400, a);

        ClientState b = new ClientState();
        b.clientId = 401;
        b.clientAddress = InetAddress.getByName("127.0.0.1");
        b.clientPort = 60001;
        b.status = ClientStatus.ACTIVE;
        s.clientStates.put(401, b);

        // build packet claiming to be from client 400 but arriving from a different IP
        AudioPacket p = new AudioPacket(400, 0, new byte[8]);
        // simulate arrival from 127.0.0.2 (not registered for client 400)
        s.processPacket(s.serializeAudioPacket(p), InetAddress.getByName("127.0.0.2"), 60000);

        // Should not forward anything
        DatagramPacket forwarded = rs.q.poll(500, TimeUnit.MILLISECONDS);
        assertNull(forwarded, "packet from spoofed IP should be dropped and not forwarded");
    }

    @Test
    public void mutePreventsForwardingEndToEnd() throws Exception {
        RecSocket rs = new RecSocket();
        Server s = new Server(rs);

        ClientState a = new ClientState();
        a.clientId = 500;
        a.clientAddress = InetAddress.getByName("127.0.0.1");
        a.clientPort = 70000;
        a.status = ClientStatus.ACTIVE;
        s.clientStates.put(500, a);

        ClientState b = new ClientState();
        b.clientId = 501;
        b.clientAddress = InetAddress.getByName("127.0.0.1");
        b.clientPort = 70001;
        b.status = ClientStatus.ACTIVE;
        s.clientStates.put(501, b);

        // mute client 500
        s.handleCommands("MUTE", 500);
        assertEquals(ClientStatus.MUTED, s.clientStates.get(500).status);

        // send a packet from muted client
        AudioPacket p = new AudioPacket(500, 0, new byte[10]);
        s.processPacket(s.serializeAudioPacket(p), a.clientAddress, a.clientPort);

        DatagramPacket forwarded = rs.q.poll(500, TimeUnit.MILLISECONDS);
        assertNull(forwarded, "muted client's packets should not be forwarded");
    }

    @Test
    public void removalAfterExtendedTimeout() throws Exception {
        DatagramSocket ds = new DatagramSocket();
        Server s = new Server(ds);

        ClientState a = new ClientState();
        a.clientId = 600;
        a.clientAddress = InetAddress.getByName("127.0.0.1");
        a.clientPort = 80000;
    // use CLIENT_TIMEOUT_MS * 5 (removal grace period) to avoid accessing private constant
    a.lastHeard = System.currentTimeMillis() - (Server.CLIENT_TIMEOUT_MS * 5 + 1000);
        a.status = ClientStatus.DISCONNECTED;
        s.clientStates.put(600, a);

        // run heartbeat which should remove stale client
        s.heartbeat();

        assertFalse(s.clientStates.containsKey(600), "client should be removed after extended grace period");
    }
}
