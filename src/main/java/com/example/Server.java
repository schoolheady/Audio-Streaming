
import java.io.IOException;
import java.net.*;


public class Server{
    private DatagramSocket socket; 
    private byte[] buffer = new byte[256];

    public Server(DatagramSocket socket) throws Exception{
        this.socket = socket;
    }

    // function that receives packages from client and sends it to other clients except origin (UDP)
    public void receiveThenSend() throws Exception{ // repeats message
        while(true){
            try {
                DatagramPacket packet = new DatagramPacket(buffer,buffer.length);
                socket.receive(packet);
                InetAddress address = packet.getAddress(); 
                int port = packet.getPort();
                String messageFromClient = new String(packet.getData(), 0, packet.getLength());
                System.out.println("[SERVER] - Received message from client: " + messageFromClient);
                packet = new DatagramPacket(packet.getData(), packet.getLength(), address, port);

                socket.send(packet);
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        
        }

    }

    // function that handles commands from client (TCP)
    public void handleCommands() throws Exception{
        // TO-DO
    }

    
}