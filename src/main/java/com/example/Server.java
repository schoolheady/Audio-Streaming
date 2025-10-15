package com.example;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class Server{
    private DatagramSocket socket; 
    private byte[] buffer = new byte[256];
    private Map<String, ClientInfo> clients = new HashMap<>();

    private final ExecutorService workerPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final ConcurrentHashMap<Integer, ClientState> clientStates;
    private static final int MAX_PACKET_SIZE = 1500;

    public Server(DatagramSocket socket) throws Exception{
        this.clientStates = new ConcurrentHashMap<>();
        this.socket = socket;
    }


    public void startServer() throws Exception{
        System.out.println("[SERVER] - Server started");
        ServerSocket server = null;
        server = new ServerSocket(4444);
        server.accept(); //blocking
        // should use TCP to check users that joined the server
        // check active commands from clients
        // receive packages from clients and send it to other clients (UDP)
    }

    // function that receives packages from client and sends it to other clients except origin (UDP)
    public void udpReceive() throws Exception{ 
        try {
            while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                byte[] buf = new byte[MAX_PACKET_SIZE];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketException se) {
                    // If socket was closed as part of shutdown, exit gracefully
                    if (socket.isClosed()) {
                        System.out.println("[UDP] - Socket closed, stopping udpReceive");
                        break;
                    }
                    throw se;
                }

                // copy only valid bytes
                byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
                InetAddress srcAddr = packet.getAddress();
                int srcPort = packet.getPort();
                System.out.println("[SERVER] - Received packet from " + srcAddr + ":" + srcPort + " with length " + copy.length);
                System.out.println("[UDP] - Submitting work to workerPool for " + srcAddr + ":" + srcPort);
                try {
                    workerPool.submit(() -> {
                        System.out.println("[UDP] - Worker start for " + srcAddr + ":" + srcPort + " on thread " + Thread.currentThread().getName());
                        try {
                            processPacket(copy, srcAddr, srcPort);
                        } catch (Exception e) {
                            System.err.println("[UDP] - Worker caught exception while processing packet from " + srcAddr + ":" + srcPort + " -> " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException rex) {
                    System.err.println("[UDP] - Task submission rejected: " + rex.getMessage());
                    rex.printStackTrace();
                }
            }
        } finally {
            System.out.println("[UDP] - udpReceive exiting");
        }
    }

    public void stop() {
        System.out.println("[SERVER] - Stopping server...");
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception e) {
            // ignore
        }
        try {
            workerPool.shutdownNow();
        } catch (Exception e) {
            // ignore
        }
        System.out.println("[SERVER] - Stopped");
    }

    // function that handles commands from client (TCP)
    public void handleCommands() throws Exception{
        // TO-DO
    }

    // heartbeat function to check users in the server (TCP)
    public void checkUsers() throws Exception{
        // TO-DO
    }

    // users need to connect through TCP first before sending/receiving UDP packages
    public void tcpConnection() throws Exception{
        Socket socket = null;
        InputStreamReader input = null;
        OutputStreamWriter output = null;
        BufferedReader reader = null;
        BufferedWriter writer = null;
        ServerSocket server = null;

        server = new ServerSocket(4444);

        while(true){
            try {
                socket = server.accept(); //blocking
                clients.put(socket.getInetAddress().toString(), new ClientInfo());
                
                input = new InputStreamReader(socket.getInputStream());
                output = new OutputStreamWriter(socket.getOutputStream());
                reader = new BufferedReader(input);
                writer = new BufferedWriter(output);

                String message = reader.readLine();
                System.out.println("[SERVER] - Received message from client: " + message);
                writer.write("Message received\n");
                writer.flush();

            } catch (IOException e) {
                e.printStackTrace();
                break;  
            } finally {
                try {
                    if (reader != null) reader.close();
                    if (writer != null) writer.close();
                    if (socket != null) socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }
    public void processPacket(byte[] data, InetAddress srcAddr, int srcPort) throws Exception {
        System.out.println("[PROCESS] - Enter processPacket: src=" + srcAddr + ":" + srcPort + " size=" + (data==null?0:data.length));
        // Deserialize the audio packet
        AudioPacket audioPacket = null;
        try {
            audioPacket = deserializeAudioPacket(data);
            System.out.println("[PROCESS] - Deserialized packet: clientId=" + audioPacket.clientId + " seq=" + audioPacket.sequenceNumber + " audioLen=" + (audioPacket.audioData==null?0:audioPacket.audioData.length));
        } catch (Exception e) {
            System.err.println("[PROCESS] - Failed to deserialize packet from " + srcAddr + ":" + srcPort + " -> " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Get or create per-client state
        ClientState state = clientStates.computeIfAbsent(audioPacket.clientId, id -> new ClientState());

        synchronized (state) {
            // Update last seen info
            state.lastHeard = System.currentTimeMillis();
            state.clientAddress = srcAddr; // store InetAddress directly
            state.clientPort = srcPort;
            state.clientId = audioPacket.clientId;

            // Add packet to buffer
            System.out.println("[PROCESS] - Buffering packet seq=" + audioPacket.sequenceNumber + " for client=" + audioPacket.clientId);
            state.buffer.put(audioPacket.sequenceNumber, audioPacket);

            // Process in-order packets starting from expectedSeq
            List<AudioPacket> toSend = new ArrayList<>();
            while (state.buffer.containsKey(state.expectedSeq)) {
                AudioPacket next = state.buffer.remove(state.expectedSeq);
                System.out.println("[PROCESS] - Emitting in-order packet seq=" + next.sequenceNumber + " for client=" + next.clientId);
                toSend.add(next);
                state.expectedSeq++;
            }

            // Forward collected packets to other clients
            if (!toSend.isEmpty()) {
                System.out.println("[PROCESS] - Forwarding " + toSend.size() + " packets from client=" + audioPacket.clientId);
                for (ClientState clientState : clientStates.values()) { // eventually use a list of clients connected via TCP
                    if (clientState.clientId == audioPacket.clientId) continue; // skip sender

                    if (clientState.clientAddress == null) {
                        System.out.println("[PROCESS] - Skipping clientId=" + clientState.clientId + " because address is null");
                        continue;
                    }
                    if (clientState.clientPort <= 0) {
                        System.out.println("[PROCESS] - Skipping clientId=" + clientState.clientId + " because port is invalid: " + clientState.clientPort);
                        continue;
                    }

                    for (AudioPacket pkt : toSend) {
                        try {
                            byte[] serialized = serializeAudioPacket(pkt);
                            DatagramPacket outPacket = new DatagramPacket(
                                serialized,
                                serialized.length,
                                clientState.clientAddress,
                                clientState.clientPort
                            );
                            socket.send(outPacket);
                            System.out.println("[PROCESS] - Sent packet seq=" + pkt.sequenceNumber + " to " + clientState.clientAddress + ":" + clientState.clientPort);
                        } catch (Exception e) {
                            System.err.println("[PROCESS] - Failed to send packet seq=" + pkt.sequenceNumber + " to clientId=" + clientState.clientId + " -> " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }


    // 1 byte client ID 4 bytes sequence number 2 bytes audio data length 320 bytes audio data
    public AudioPacket deserializeAudioPacket(byte[] data) {
        int clientId = data[0] & 0xFF; // ensure unsigned
        int sequenceNumber = ((data[1] & 0xFF) << 24)
                        |    ((data[2] & 0xFF) << 16)
                        |    ((data[3] & 0xFF) << 8)
                        |    (data[4] & 0xFF);
        int audioDataLength = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
        
        // safety check
        if (audioDataLength > data.length - 7) {
            throw new IllegalArgumentException("Audio data length exceeds packet size");
        }

        byte[] audioData = Arrays.copyOfRange(data, 7, 7 + audioDataLength);
        return new AudioPacket(clientId, sequenceNumber, audioData);
    }

    public byte[] serializeAudioPacket(AudioPacket pkt) {
        byte[] data = new byte[7 + pkt.audioData.length];
        data[0] = (byte) pkt.clientId;
        data[1] = (byte) (pkt.sequenceNumber >> 24);
        data[2] = (byte) (pkt.sequenceNumber >> 16);
        data[3] = (byte) (pkt.sequenceNumber >> 8);
        data[4] = (byte) pkt.sequenceNumber;
        data[5] = (byte) (pkt.audioData.length >> 8);
        data[6] = (byte) pkt.audioData.length;
        System.arraycopy(pkt.audioData, 0, data, 7, pkt.audioData.length);
        return data;
    }

}

class ClientInfo {
    String id;
    InetAddress address;
    int udpPort;
}


class ClientState {
    int clientId; 
    int clientPort; 
    InetAddress clientAddress; 
    int expectedSeq = 0; 
    NavigableMap<Integer, AudioPacket> buffer = new TreeMap<>(); 
        // holds packets keyed by sequence number
    long lastHeard; // timestamp of last packet from this client
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
