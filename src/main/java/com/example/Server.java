
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
        // should use TCP to check users that joined the server
        // check active commands from clients
        // receive packages from clients and send it to other clients (UDP)
    }

    // function that receives packages from client and sends it to other clients except origin (UDP)
    private void udpReceive() throws Exception{ 
            while (!Thread.currentThread().isInterrupted()) {
                byte[] buf = new byte[MAX_PACKET_SIZE];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                // copy only valid bytes
                byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
                InetAddress srcAddr = packet.getAddress();
                int srcPort = packet.getPort();
                System.out.println("[SERVER] - Received packet from " + srcAddr + ":" + srcPort + " with length " + copy.length);
                workerPool.submit(() -> {
                    try {
                            processPacket(copy, srcAddr, srcPort);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                
            }
    }

    // function that handles commands from client (TCP)
    private void handleCommands() throws Exception{
        // TO-DO
    }

    // heartbeat function to check users in the server (TCP)
    private void checkUsers() throws Exception{
        // TO-DO
    }

    // users need to connect through TCP first before sending/receiving UDP packages
    private void tcpConnection() throws Exception{
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

    private void processPacket(byte[] data, InetAddress srcAddr, int srcPort) throws Exception{
        // Deserialize the audio packet
        AudioPacket audioPacket = deserializeAudioPacket(data);

        // Update client state
        ClientState state = clientStates.computeIfAbsent(audioPacket.clientId, id -> new ClientState());
        state.lastHeard = System.currentTimeMillis();

        state.clientAddress = srcAddr.toString();
        state.clientPort = srcPort;
        state.clientId = audioPacket.clientId;
        // Check for expected sequence number
        if (audioPacket.sequenceNumber == state.expectedSeq) {
            // Process the packet
            System.out.println("[SERVER] - Processing audio packet from " + srcAddr + ":" + srcPort);
            state.expectedSeq++;
        } else {
            // Out of order packet
            System.out.println("[SERVER] - Out of order packet from " + srcAddr + ":" + srcPort);
            state.buffer.put(audioPacket.sequenceNumber, audioPacket);
        }
    }

    // 1 byte client ID 4 bytes sequence number 2 bytes audio data length 320 bytes audio data
    private AudioPacket deserializeAudioPacket(byte[] data) {
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

}



class ClientState {
    int clientId; 
    int clientPort; 
    String clientAddress; 
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
