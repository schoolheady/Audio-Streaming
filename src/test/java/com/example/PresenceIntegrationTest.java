package com.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class PresenceIntegrationTest {

    private Server server;

    @AfterEach
    public void tearDown() throws Exception {
        if (server != null) server.stop();
    }

    @Test
    public void presenceAddBroadcastsToExistingTcpClients() throws Exception {
        server = new Server(new DatagramSocket());
        server.startTCPServer();
        Thread.sleep(200);

        // Connect a passive TCP client (will be in tcpClients but won't register)
    Socket passive = new Socket("127.0.0.1", server.getTcpPort());
        passive.setSoTimeout(2000);
        BufferedReader pr = new BufferedReader(new InputStreamReader(passive.getInputStream()));

        // Now connect another TCP client and register; passive should receive PRESENCE ADD
    Socket active = new Socket("127.0.0.1", server.getTcpPort());
        BufferedWriter aw = new BufferedWriter(new OutputStreamWriter(active.getOutputStream()));
        BufferedReader ar = new BufferedReader(new InputStreamReader(active.getInputStream()));

        // Active registers
        aw.write("REGISTER 54321 Alice\n");
        aw.flush();

        // Read OK on active
        String ok = ar.readLine();
        assertNotNull(ok);
        assertTrue(ok.startsWith("OK "));

        // Passive should get PRESENCE ADD line
        String pres = null;
        try {
            pres = pr.readLine();
        } catch (SocketTimeoutException ste) {
            // allow null to assert later
        }
        assertNotNull(pres, "Passive client should receive a PRESENCE ADD");
        assertTrue(pres.startsWith("PRESENCE ADD "));

        // cleanup sockets
        active.close();
        passive.close();
    }

    @Test
    public void presenceRemoveBroadcastsOnLeave() throws Exception {
        server = new Server(new DatagramSocket());
        server.startTCPServer();
        Thread.sleep(200);

        // Create two clients
    Socket listener = new Socket("127.0.0.1", server.getTcpPort());
        listener.setSoTimeout(2000);
        BufferedReader lr = new BufferedReader(new InputStreamReader(listener.getInputStream()));

    Socket actor = new Socket("127.0.0.1", server.getTcpPort());
        actor.setSoTimeout(2000);
        BufferedWriter aw = new BufferedWriter(new OutputStreamWriter(actor.getOutputStream()));
        BufferedReader ar = new BufferedReader(new InputStreamReader(actor.getInputStream()));

        // actor registers
        aw.write("REGISTER 40000 Bob\n");
        aw.flush();
        String ok = ar.readLine();
        assertNotNull(ok);
        assertTrue(ok.startsWith("OK "));

        // listener should get PRESENCE ADD
        String pres = lr.readLine();
        assertNotNull(pres);
        assertTrue(pres.startsWith("PRESENCE ADD "));

        // Now actor sends LEAVE command; server should broadcast PRESENCE REMOVE
        aw.write("LEAVE\n");
        aw.flush();

        // listener should receive PRESENCE REMOVE
        String rem = lr.readLine();
        assertNotNull(rem);
        assertTrue(rem.startsWith("PRESENCE REMOVE "));

        actor.close();
        listener.close();
    }

    @Test
    public void reRegisterReusesClientIdForSameEndpoint() throws Exception {
        server = new Server(new DatagramSocket());
        server.startTCPServer();
        Thread.sleep(200);

        // First registration on UDP port 32100
    Socket s1 = new Socket("127.0.0.1", server.getTcpPort());
        BufferedWriter w1 = new BufferedWriter(new OutputStreamWriter(s1.getOutputStream()));
        BufferedReader r1 = new BufferedReader(new InputStreamReader(s1.getInputStream()));
        w1.write("REGISTER 32100 Charlie\n");
        w1.flush();
        String ok1 = r1.readLine();
        assertNotNull(ok1);
        assertTrue(ok1.startsWith("OK "));
        int id1 = Integer.parseInt(ok1.split("\\s+")[1]);

        // Close socket but keep client state (simulate disconnect)
        s1.close();
        Thread.sleep(50);

        // Reconnect and register again from same IP and same UDP port => should reuse id
    Socket s2 = new Socket("127.0.0.1", server.getTcpPort());
        BufferedWriter w2 = new BufferedWriter(new OutputStreamWriter(s2.getOutputStream()));
        BufferedReader r2 = new BufferedReader(new InputStreamReader(s2.getInputStream()));
        w2.write("REGISTER 32100 Charlie\n");
        w2.flush();
        String ok2 = r2.readLine();
        assertNotNull(ok2);
        assertTrue(ok2.startsWith("OK "));
        int id2 = Integer.parseInt(ok2.split("\\s+")[1]);

        assertEquals(id1, id2, "Re-register from same endpoint should reuse the same clientId");

        s2.close();
    }
}
