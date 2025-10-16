package com.example;

import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

public class ServerBehaviorTest {

    @Test
    public void mutePreventsForwarding() throws Exception {
        DatagramSocket ds = new DatagramSocket();
        Server s = new Server(ds);

        ClientState a = new ClientState();
        a.clientId = 1;
        a.clientAddress = InetAddress.getByName("127.0.0.1");
        a.clientPort = 10001;
        a.status = ClientStatus.ACTIVE;

        ClientState b = new ClientState();
        b.clientId = 2;
        b.clientAddress = InetAddress.getByName("127.0.0.1");
        b.clientPort = 10002;
        b.status = ClientStatus.ACTIVE;

        s.clientStates.put(1, a);
        s.clientStates.put(2, b);

        // mute client 1
        s.handleCommands("MUTE", 1);
        assertEquals(ClientStatus.MUTED, s.clientStates.get(1).status);
    }

    @Test
    public void udpPortUpdateOnSameIp() throws Exception {
        DatagramSocket ds = new DatagramSocket();
        Server s = new Server(ds);

        ClientState a = new ClientState();
        a.clientId = 3;
        a.clientAddress = InetAddress.getByName("127.0.0.1");
        a.clientPort = 20000;
        a.status = ClientStatus.ACTIVE;
        s.clientStates.put(3, a);

        // build audio packet for clientId 3
        byte[] audio = new byte[10];
        AudioPacket pkt = new AudioPacket(3, 0, audio);
        byte[] data = s.serializeAudioPacket(pkt);

        // simulate packet arriving from same IP but different port
        s.processPacket(data, InetAddress.getByName("127.0.0.1"), 20001);

        assertEquals(20001, s.clientStates.get(3).clientPort);
    }

    @Test
    public void heartbeatMarksDisconnected() throws Exception {
        DatagramSocket ds = new DatagramSocket();
        Server s = new Server(ds);

        ClientState a = new ClientState();
        a.clientId = 4;
        a.clientAddress = InetAddress.getByName("127.0.0.1");
        a.clientPort = 30000;
        a.status = ClientStatus.ACTIVE;
        a.lastHeard = System.currentTimeMillis() - (Server.CLIENT_TIMEOUT_MS + 1000);
        s.clientStates.put(4, a);

        s.heartbeat();

        assertEquals(ClientStatus.DISCONNECTED, s.clientStates.get(4).status);
    }
}
