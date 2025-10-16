import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.LineUnavailableException;

public class VoiceChatClient {
    private static final int TCP_PORT = 5000;
    private static final int UDP_PORT = 5001; // This is the port the socket will bind to
    private static final String SERVER_IP = "127.0.0.1";
    private static final int INITIAL_CLIENT_ID = 123; 

    private TcpControlChannel tcpChannel;
    private AudioHandler audioHandler;

    private final AtomicBoolean isMute = new AtomicBoolean(false);

    public VoiceChatClient() {
        try {
            // Pass the initial CLIENT_ID. It will be updated by the TCP channel upon receiving "OK <clientId>".
            tcpChannel = new TcpControlChannel(SERVER_IP, TCP_PORT, INITIAL_CLIENT_ID);
            
            // Initialize AudioHandler, passing the single socket's port (UDP_PORT) and the isMute reference
            InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
            audioHandler = new AudioHandler(serverAddr, UDP_PORT, INITIAL_CLIENT_ID, isMute);

        } catch (LineUnavailableException | SocketException | UnknownHostException e) {
            System.err.println("Initialization Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void joinSession() {
        if (audioHandler == null) {
            System.err.println("Audio Handler not initialized. Cannot join session.");
            return;
        }
        
        if (tcpChannel.connect()) {
            System.out.println("Connected to TCP. Sending REGISTER command....");
            
            // 1. Get the local UDP port of the bound socket
            int localUdpPort = audioHandler.getLocalPort();
            
            // 2. Send the REGISTER command
            if (tcpChannel.register(localUdpPort)) {
                
                // Client ID will be updated asynchronously in TcpControlChannel.tcpListener() 
                
                // 3. Start streaming/receiving (UDP)
                audioHandler.startStreaming();
                audioHandler.startReceiving();
                
                // 4. Send the JOIN command
                tcpChannel.sendCommand("JOIN"); 
            } else {
                System.err.println("Failed to send REGISTER command.");
                tcpChannel.disconnect();
            }
        } else {
            System.err.println("Cannot connect to TCP server.");
        }
    }

    public void leaveSession() {
        if (tcpChannel.isConnected()) {
            System.out.println("Sending LEAVE command...");
            tcpChannel.sendCommand("LEAVE"); 
            tcpChannel.disconnect();
        }
        
        if (audioHandler != null) {
            audioHandler.stopStreaming();
            audioHandler.stopReceiving();
        }
        System.out.println("Left the session.");
    }

    public void toggleMute() {
        boolean mute = !isMute.get();
        isMute.set(mute);
        String command = mute ? "MUTE" : "UNMUTE"; 
        
        if (tcpChannel.isConnected()) {
            tcpChannel.sendCommand(command);
        }
        System.out.println("Changed mute state to: " + (mute ? "Muted" : "Unmuted"));
    }

    public static void main(String[] args) {
        VoiceChatClient client = new VoiceChatClient();
        client.joinSession();

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String command;
            System.out.println("Enter commands: join, leave, mute, unmute");
            while (client.tcpChannel.isConnected() && (command = in.readLine()) != null) {
                if (command.equalsIgnoreCase("leave")) {
                    client.leaveSession();
                    break;
                } else if (command.equalsIgnoreCase("mute")) {
                    if (!client.isMute.get()) {
                        client.toggleMute();
                    }
                } else if (command.equalsIgnoreCase("unmute")) {
                    if (client.isMute.get()) {
                        client.toggleMute();
                    }
                } else {
                    System.out.println("Unknown command: " + command);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            client.leaveSession();
        }
    }
}