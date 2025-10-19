package com.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.audiostreaming.Server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

public class UsernameChangeTest {

    private Server server;

    @AfterEach
    public void tearDown() throws Exception {
        if (server != null) server.stop();
    }

    @Test
    public void clientCanChangeUsernameOnReregister() throws Exception {
        server = new Server(new DatagramSocket());
        server.startTCPServer();
        Thread.sleep(200);

        // Create listener to see presence messages
        Socket listener = new Socket("127.0.0.1", server.getTcpPort());
        listener.setSoTimeout(3000);
        BufferedReader lr = new BufferedReader(new InputStreamReader(listener.getInputStream()));

        // Client 1 registers with username "Alice"
        Socket client = new Socket("127.0.0.1", server.getTcpPort());
        client.setSoTimeout(3000);
        BufferedWriter cw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
        BufferedReader cr = new BufferedReader(new InputStreamReader(client.getInputStream()));

        cw.write("REGISTER 60001 Alice\n");
        cw.flush();
        String ok1 = cr.readLine();
        assertNotNull(ok1);
        assertTrue(ok1.startsWith("OK "));
        int clientId = Integer.parseInt(ok1.split("\\s+")[1]);

        // Listener should see PRESENCE ADD for Alice
        String pres1 = lr.readLine();
        assertNotNull(pres1);
        assertTrue(pres1.contains("Alice"), "Expected PRESENCE ADD with 'Alice', got: " + pres1);

        // Client leaves
        cw.write("LEAVE\n");
        cw.flush();
        Thread.sleep(100);

        // Listener should see PRESENCE REMOVE
        String remove1 = lr.readLine();
        assertNotNull(remove1);
        assertTrue(remove1.startsWith("PRESENCE REMOVE"));

        // Client disconnects TCP
        client.close();
        Thread.sleep(100);

        // Client reconnects with NEW username "Bob" (same UDP port, so will reuse clientId)
        Socket client2 = new Socket("127.0.0.1", server.getTcpPort());
        client2.setSoTimeout(3000);
        BufferedWriter cw2 = new BufferedWriter(new OutputStreamWriter(client2.getOutputStream()));
        BufferedReader cr2 = new BufferedReader(new InputStreamReader(client2.getInputStream()));

        cw2.write("REGISTER 60001 Bob\n");
        cw2.flush();
        String ok2 = cr2.readLine();
        assertNotNull(ok2);
        assertTrue(ok2.startsWith("OK "));
        int clientId2 = Integer.parseInt(ok2.split("\\s+")[1]);

        // Should reuse same clientId
        assertEquals(clientId, clientId2, "Client should reuse same ID when reconnecting from same endpoint");

        // Listener should see PRESENCE ADD for Bob (NOT Alice or Bob#1)
        String pres2 = lr.readLine();
        assertNotNull(pres2);
        System.out.println("Received presence message: " + pres2);
        assertTrue(pres2.contains("Bob"), "Expected PRESENCE ADD with 'Bob', got: " + pres2);
        assertFalse(pres2.contains("Alice"), "Should not contain old username 'Alice'");
        assertFalse(pres2.contains("#"), "Should not have duplicate suffix since Alice was removed");

        client2.close();
        listener.close();
    }

    @Test
    public void reregisterWithSameUsernameDoesNotAddSuffix() throws Exception {
        server = new Server(new DatagramSocket());
        server.startTCPServer();
        Thread.sleep(200);

        // Client registers with username "Charlie"
        Socket client = new Socket("127.0.0.1", server.getTcpPort());
        BufferedWriter cw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
        BufferedReader cr = new BufferedReader(new InputStreamReader(client.getInputStream()));

        cw.write("REGISTER 60010 Charlie\n");
        cw.flush();
        String ok1 = cr.readLine();
        assertNotNull(ok1);
        assertTrue(ok1.startsWith("OK "));

        // Read existing presence list
        String line;
        while ((line = cr.readLine()) != null) {
            if (line.startsWith("PRESENCE ADD")) {
                System.out.println("Initial presence: " + line);
                assertTrue(line.contains("Charlie"));
                assertFalse(line.contains("#"), "Initial registration should not have suffix");
            }
            Thread.sleep(10);
            if (client.getInputStream().available() == 0) break;
        }

        // Leave and disconnect
        cw.write("LEAVE\n");
        cw.flush();
        client.close();
        Thread.sleep(100);

        // Reconnect with SAME username
        Socket client2 = new Socket("127.0.0.1", server.getTcpPort());
        BufferedWriter cw2 = new BufferedWriter(new OutputStreamWriter(client2.getOutputStream()));
        BufferedReader cr2 = new BufferedReader(new InputStreamReader(client2.getInputStream()));

        cw2.write("REGISTER 60010 Charlie\n");
        cw2.flush();
        String ok2 = cr2.readLine();
        assertNotNull(ok2);
        assertTrue(ok2.startsWith("OK "));

        // Read presence messages
        while ((line = cr2.readLine()) != null) {
            if (line.startsWith("PRESENCE ADD")) {
                System.out.println("Re-registration presence: " + line);
                if (line.contains("Charlie")) {
                    assertFalse(line.contains("#"), "Re-registering with same name should not add suffix: " + line);
                }
            }
            Thread.sleep(10);
            if (client2.getInputStream().available() == 0) break;
        }

        client2.close();
    }
}
