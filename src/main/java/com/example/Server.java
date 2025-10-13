
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.*;
import java.util.HashMap;
import java.util.Map;


public class Server{
    private DatagramSocket socket; 
    private byte[] buffer = new byte[256];
    private Map<String, ClientInfo> clients = new HashMap<>();

    public Server(DatagramSocket socket) throws Exception{
        this.socket = socket;
    }


    public void startServer() throws Exception{
        System.out.println("[SERVER] - Server started");
        // should use TCP to check users that joined the server
        // check active commands from clients
        // receive packages from clients and send it to other clients (UDP)
    }

    // function that receives packages from client and sends it to other clients except origin (UDP)
    private void receiveThenSend() throws Exception{ // repeats message
        while(true){

                DatagramPacket packet = new DatagramPacket(buffer,buffer.length);
                String messageFromClient = new String(packet.getData(), 0, packet.getLength());
                System.out.println("[SERVER] - Received message from client: " + messageFromClient);

                for (ClientInfo c : clients.values()) {
                    // Assuming you want to forward the received packet to other clients
                    // We need to create a new packet to send
                    DatagramPacket sendPacket = new DatagramPacket(packet.getData(), packet.getLength(), c.address, c.udpPort);
                    socket.send(sendPacket);
                }

        
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

    private void handleAudioStream() throws Exception{
        // TO-DO
        int clientid = 0;
        int sequenceNumber = 0;
        byte[] audioBuffer = new byte[1024]; 

        // send the audio stream in order of sequence number
        // TO-DO


    }

    
}

class ClientInfo {
    String id;
    InetAddress address;
    int udpPort;
}
