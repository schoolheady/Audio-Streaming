package com.audiostreaming;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;

import java.io.OutputStreamWriter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.*;

import java.util.Arrays;
import java.util.Map;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;



public class Server{

    Queue<AudioPacket> audioQueue = new ConcurrentLinkedQueue<>();


    private final DatagramSocket udpSocket; 
    private static final Logger logger = Logger.getLogger(Server.class.getName());

    final ConcurrentHashMap<Integer, ClientState> clientStates;

    private ServerSocket tcpServerSocket;
    private final int UDP_PORT = 5555;
    private final int TCP_PORT = 4444;

    private final AtomicInteger idCounter = new AtomicInteger(1);

    AtomicBoolean serverRunning = new AtomicBoolean(false);
    public Server() throws Exception{
        this.clientStates = new ConcurrentHashMap<>();
        this.udpSocket = new DatagramSocket(UDP_PORT);
    }

    public void startUDPServer() throws Exception {
        System.out.println("[Server] UDP Server starting...");
            logger.info("[SERVER] - Server starting UDP receiver");
            new Thread(() -> {
                try {
                    udpReceive();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "[UDP] - receive loop error", e);
                }
            }, "udp-receive-thread").start();
            new Thread(() -> {
                try {
                    udpSend();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "[UDP] - send loop error", e);
                }
            }, "udp-send-thread").start();
        
    }

    public void startTCPServer() throws Exception{
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        try {
            ss.bind(new InetSocketAddress(TCP_PORT));
        } catch (java.net.BindException be) {
            logger.log(Level.WARNING, "[TCP] - Port " + TCP_PORT + " unavailable, falling back to ephemeral port", be);
            ss.bind(new InetSocketAddress(0));
        }
        startTCPServer(ss);
    }

    private void startTCPHeartbeatMonitor() {
        new Thread(() -> {
            while (serverRunning.get()) {
                long now = System.currentTimeMillis();
                for (Map.Entry<Integer, ClientState> entry : clientStates.entrySet()) {
                    ClientState state = entry.getValue();
                    if (now - state.lastPingTime > 15000) { // 15 seconds timeout
                        state.status = ClientStatus.DISCONNECTED;
                        logger.info("[TCP] - Client " + state.clientId + " marked as DISCONNECTED due to timeout");
                    }
                }
                try {
                    Thread.sleep(5000); // Check every 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "tcp-heartbeat-monitor-thread").start();
    }

    public void udpReceive() {
        byte[] buffer = new byte[1500];
        while (serverRunning.get()) {
            if(clientStates.isEmpty()){
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(packet);
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

                int clientId = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) |
                            ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                int sequenceNumber = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) |
                                    ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
                int audioDataLength = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);

                if (data.length >= 10 + audioDataLength) {
                    byte[] audioData = Arrays.copyOfRange(data, 10, 10 + audioDataLength);
                    audioQueue.add(new AudioPacket(clientId, sequenceNumber, audioData));
                    //System.out.println("Received audio packet from client " + clientId + " seq=" + sequenceNumber + " audioDataLength=" + audioDataLength);
                } else {
                    logger.warning("[UDP] - Packet shorter than expected");
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "[UDP] - Error receiving UDP packet", e);
            }
        }
    }

    public void udpSend() throws Exception {
        while (serverRunning.get()) {
            if (clientStates.isEmpty() || audioQueue.isEmpty()) {
                Thread.sleep(10);
                continue;
            }

            AudioPacket packet = audioQueue.poll();
            if (packet == null) continue;

            byte[] audioData = packet.audioData;
            int senderId = packet.clientId;

            // Prepare UDP payload
            byte[] data = new byte[10 + audioData.length];
            data[0] = (byte) ((senderId >> 24) & 0xFF);
            data[1] = (byte) ((senderId >> 16) & 0xFF);
            data[2] = (byte) ((senderId >> 8) & 0xFF);
            data[3] = (byte) (senderId & 0xFF);

            data[4] = (byte) ((packet.sequenceNumber >> 24) & 0xFF);
            data[5] = (byte) ((packet.sequenceNumber >> 16) & 0xFF);
            data[6] = (byte) ((packet.sequenceNumber >> 8) & 0xFF);
            data[7] = (byte) (packet.sequenceNumber & 0xFF);

            data[8] = (byte) ((audioData.length >> 8) & 0xFF);
            data[9] = (byte) (audioData.length & 0xFF);

            System.arraycopy(audioData, 0, data, 10, audioData.length);

            // Forward to all other active clients
            for (Map.Entry<Integer, ClientState> entry : clientStates.entrySet()) {
                ClientState recipient = entry.getValue();

                // Skip sender itself
                if (recipient.clientId == senderId) continue;

                // Only send to active clients with valid UDP ports
                if (recipient.status != ClientStatus.LEFT &&
                    recipient.status != ClientStatus.DISCONNECTED &&
                    recipient.clientUdpPort > 0) {
                    try {
                        DatagramPacket udpPacket = new DatagramPacket(data, data.length,
                                                                    recipient.clientAddress,
                                                                    recipient.clientUdpPort);
                        udpSocket.send(udpPacket);
                    } catch (Exception e) {
                        System.err.println("Failed to send UDP packet to client " + recipient.clientId);
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public void startTCPServer(ServerSocket providedSocket) throws Exception {
        serverRunning.set(true); 
        startTCPHeartbeatMonitor();
        this.tcpServerSocket = providedSocket;
        logger.info("[SERVER] - Server starting TCP acceptor");
        try {
            System.out.println("==============================================");
            System.out.println("[SERVER] - TCP Port: " + tcpServerSocket.getLocalPort());
            System.out.println("[SERVER] - UDP Port: " + udpSocket.getLocalPort());    

            System.out.println("----------------------------------------------");
            System.out.println("==============================================");
        } catch (Exception e) {
            System.out.println("[SERVER] - Error: " + e.getMessage());
        }
        

        new Thread(() -> {
            try {
                tcpAcceptLoop();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[TCP] - accept loop error", e);
            }
        }, "tcp-accept-thread").start();

    }


    private void tcpAcceptLoop() throws Exception {
        try{
            while (!tcpServerSocket.isClosed()) {
                Socket s = tcpServerSocket.accept();
                new Thread(new ControlHandler(s)).start();
                
            }
        } finally {
            try { if (tcpServerSocket != null && !tcpServerSocket.isClosed()) tcpServerSocket.close(); } catch (IOException ignored) {}
        }
    }


    private void send(BufferedWriter out, String msg) throws IOException {
        out.write(msg);
        out.newLine();
        out.flush();
    }

    private void handleRequests(String cmd, BufferedWriter out, int clientId) throws IOException {
        ClientState state = clientStates.get(clientId);
        if (state == null) {
            send(out, "ERR Client not registered or already disconnected");
            return;
        }

        String[] parts = cmd.split("\\s+");
        String action = parts[0].toUpperCase();

        switch (action) {
            case "CHANGE_USERNAME" -> {
                state.username = parts.length > 1 ? parts[1] : state.username;
                send(out, "USERNAME_OK " + state.username);
            }
            case "LIST_USERS" -> sendUsersInCall(out);
            default -> send(out, "ERR Unknown command: " + cmd);
        }
    }

    private boolean handleCommand(String cmd, BufferedWriter out, int clientId) throws IOException {
        //System.out.println("[TCP] Received command from client " + clientId + ": " + cmd);
        ClientState state = clientStates.get(clientId);
        if (state == null) {
            send(out, "ERR Client not registered or already disconnected");
            return true; // stop further processing
        }

        String[] parts = cmd.split("\\s+");
        String action = parts[0].toUpperCase();
        switch (action) {
            case "JOIN" -> {
                System.out.println("[TCP] Client " + clientId + " joining session.");
                clientStates.get(clientId).status = ClientStatus.ACTIVE;
                System.out.println("Status of client " + clientId + ": " + clientStates.get(clientId).status);
                send(out, "JOIN_OK");
                return true;
            }

            case "LEAVE" -> {
                clientStates.get(clientId).status = ClientStatus.LEFT;
                clientStates.remove(clientId);
                send(out, "LEAVE_OK");
                return true;
            }

            case "MUTE" -> {
                clientStates.get(clientId).status = ClientStatus.MUTED;
                send(out, "MUTE_OK");
                return true;
            }

            case "UNMUTE" -> {
                clientStates.get(clientId).status = ClientStatus.ACTIVE;
                send(out, "UNMUTE_OK");
                return true;
            }


            case "PING" -> {
                clientStates.get(clientId).lastPingTime = System.currentTimeMillis();
                
                send(out, "PONG");
                return true;
            }   

            default -> {
                System.out.println("No handler for command in <handleCommand> will handle in <handleRequests>: " + cmd);
                return false;
            }
        }
    }

    // checks how many similar usernames exist
    int checkUsernamereAvailable(String username) {
        int count = 0;
        for (ClientState state : clientStates.values()) {
            if (state.username.equalsIgnoreCase(username)) {
                count++;
            }
        }
        return count;
    }
  





    public int getTcpPort() {
        return tcpServerSocket == null ? -1 : tcpServerSocket.getLocalPort();
    }

    public void stopServer() {
        serverRunning.set(false);
        try { if (tcpServerSocket != null && !tcpServerSocket.isClosed()) tcpServerSocket.close(); } catch (IOException ignored) {}
        try { if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close(); } catch (Exception ignored) {}
    }

    // send string array of all users in the call so users not disconnected or left
    private void sendUsersInCall(BufferedWriter out) throws IOException {
        StringBuilder userList = new StringBuilder("USERS");
        for (ClientState state : clientStates.values()) {
            if (state.status != ClientStatus.DISCONNECTED && state.status != ClientStatus.LEFT) {
                userList.append(" ").append(state.username);
            }
        }
        send(out, "OK " + userList.toString());
    }

    private class ControlHandler implements Runnable {
        private final Socket tcpSocket;

        ControlHandler(Socket tcpSocket) {
            this.tcpSocket = tcpSocket;
        }

        @Override
        public void run() {
            try (
                BufferedReader in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(tcpSocket.getOutputStream()));
            ) {
                // Initial handshake to get client udp
                int clientId = idCounter.getAndIncrement();
                ClientState state = new ClientState();
                state.clientId = clientId;
                state.clientAddress = tcpSocket.getInetAddress();
                state.clientPort = tcpSocket.getPort();
                state.lastPingTime = System.currentTimeMillis();
                state.clientUdpPort = -1; // to be set later
                state.username = "User" + clientId; // get username from client later
                clientStates.put(clientId, state);

                
                // while the client still exists, process commands
                String line;
                System.out.println("[SERVER] - Waiting for REGISTER from client");
                while ((line = in.readLine()) != null) {
                    // continue only after the udp port is set

                    if(state.clientUdpPort != -1){
                        if(handleCommand(line, out, clientId)) {
                            continue;
                        }
                        handleRequests(line, out, clientId);
                    }
                    else{
                        if(line.startsWith("REGISTER")){
                            String[] parts = line.split("\\s+");
                            if (parts.length >= 2) {
                                try {
                                    int udpPort = Integer.parseInt(parts[1]);
                                    String username = (parts.length >= 3) ? parts[2] : "Guest";
                                    state.clientUdpPort = udpPort;

                                    int check = checkUsernamereAvailable(username);
                                    if(check == 0){
                                        state.username = username;
                                    } else {
                                        username = username + " #" +check;
                                        state.username = username;
                                    }
                                    send(out, "REGISTER_OK " + clientId);
                                    System.out.println("[TCP] Registered clientId=" + clientId + " udpPort=" + udpPort + " username=" + username);
                                } catch(NumberFormatException nfe) {
                                    send(out, "ERR Invalid UDP port: " + parts[1]);
                                }
                            } else {
                                send(out, "ERR Invalid REGISTER command");
                            }
                        } else {
                            send(out, "ERR UDP port not set. Please send REGISTER <port>");
                        }
                    }      
                                       
                      

                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "[TCP] - ControlHandler IOException for " + tcpSocket.getRemoteSocketAddress(), e);
            } 
        }
    }
}

































class ClientState {
    int clientId; 
    int clientPort; 
    int clientUdpPort;
    long lastPingTime;
    InetAddress clientAddress; 
    String username;
    ClientStatus status = ClientStatus.ACTIVE; // could be ACTIVE, MUTED, LEFT, DISCONNECTED
}



class AudioPacket {
    public int clientId;
    public int sequenceNumber;
    public byte[] audioData;
    public long timestamp;


    public AudioPacket(int clientId, int sequenceNumber, byte[] audioData) {
        this.clientId = clientId;
        this.sequenceNumber = sequenceNumber;
        this.audioData = audioData;
        this.timestamp = System.currentTimeMillis();
    }
}


enum ClientStatus {
    ACTIVE,
    MUTED,
    LEFT,
    DISCONNECTED
}
