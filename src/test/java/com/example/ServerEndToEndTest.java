package com.example;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ServerEndToEndTest {

    @Test
    public void endToEndTcpAndUdpFlow() throws Exception {
        // start the real server bound to UDP port 5555
        DatagramSocket serverUdp = new DatagramSocket(5555);
        Server server = new Server(serverUdp);

        // start UDP receiver thread
        Thread udpThread = new Thread(() -> {
            try { server.udpReceive(); } catch (Exception ignored) {}
        }, "srv-udp");
        udpThread.setDaemon(true);
        udpThread.start();

        // start TCP accept thread
        server.startTCPServer();
        // allow server to bind
        Thread.sleep(200);

        InetAddress localhost = InetAddress.getByName("127.0.0.1");

        // create client sockets
        DatagramSocket client1Udp = new DatagramSocket();
        DatagramSocket client2Udp = new DatagramSocket();

        // register both clients via TCP and keep the control sockets open for the duration of the test
        Socket t1 = new Socket("127.0.0.1", 4444);
        BufferedWriter w1 = new BufferedWriter(new OutputStreamWriter(t1.getOutputStream()));
        BufferedReader r1 = new BufferedReader(new InputStreamReader(t1.getInputStream()));
        w1.write("REGISTER " + client1Udp.getLocalPort() + "\n");
        w1.flush();
        String rep1 = r1.readLine();
        assertNotNull(rep1);
        assertTrue(rep1.startsWith("OK "));
        int id1 = Integer.parseInt(rep1.split("\\s+")[1]);

        Socket t2 = new Socket("127.0.0.1", 4444);
        BufferedWriter w2 = new BufferedWriter(new OutputStreamWriter(t2.getOutputStream()));
        BufferedReader r2 = new BufferedReader(new InputStreamReader(t2.getInputStream()));
        w2.write("REGISTER " + client2Udp.getLocalPort() + "\n");
        w2.flush();
        String rep2 = r2.readLine();
        assertNotNull(rep2);
        assertTrue(rep2.startsWith("OK "));
        int id2 = Integer.parseInt(rep2.split("\\s+")[1]);

        assertNotEquals(id1, id2);

        // start listener thread for client2
        CountDownLatch latch = new CountDownLatch(1);
        final byte[][] receivedHolder = new byte[1][];
        Thread client2Listener = new Thread(() -> {
            try {
                byte[] buf = new byte[1500];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                client2Udp.receive(p);
                byte[] copy = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), copy, 0, p.getLength());
                receivedHolder[0] = copy;
                latch.countDown();
            } catch (Exception ignored) {}
        }, "client2-listener");
        client2Listener.setDaemon(true);
        client2Listener.start();

        // build and send audio packet from client1
        byte[] audio = new byte[320];
        AudioPacket pkt = new AudioPacket(id1, 0, audio);
        byte[] data = server.serializeAudioPacket(pkt);
        DatagramPacket send = new DatagramPacket(data, data.length, localhost, 5555);
        client1Udp.send(send);

        boolean arrived = latch.await(3, TimeUnit.SECONDS);
        assertTrue(arrived, "client2 should receive forwarded packet within timeout");

        AudioPacket parsed = server.deserializeAudioPacket(receivedHolder[0]);
        assertEquals(id1, parsed.clientId);
        assertEquals(0, parsed.sequenceNumber);
        assertEquals(320, parsed.audioData.length);

        // cleanup: close TCP control sockets and UDP sockets
        try { t1.close(); } catch (Exception ignored) {}
        try { t2.close(); } catch (Exception ignored) {}
        client1Udp.close();
        client2Udp.close();
        server.stop();
    }
}
