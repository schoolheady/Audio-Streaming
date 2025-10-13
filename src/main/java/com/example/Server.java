
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
    private void udpReceive() throws Exception{ // repeats message
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
                        handleAudioStream(packet);
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

    private void handleAudioStream(DatagramPacket packet) throws Exception{ 
            // receive a packet from the reader
    }

    
}

class ClientInfo {
    String id;
    InetAddress address;
    int udpPort;
}

class ClientState {
    int clientId; // optional, for reference
    int expectedSeq = 0; // next sequence number we expect
    NavigableMap<Integer, AudioPacket> buffer = new TreeMap<>(); 
        // holds packets keyed by sequence number
    long lastHeard; // timestamp of last packet from this client
}


class AudioPacket {
    public int clientId;
    public long sequenceNumber;
    public byte[] audioData;
    public long timestamp;

    public AudioPacket(int clientId, long sequenceNumber, byte[] audioData) {
        this.clientId = clientId;
        this.sequenceNumber = sequenceNumber;
        this.audioData = audioData;
        this.timestamp = System.currentTimeMillis();
    }
}
