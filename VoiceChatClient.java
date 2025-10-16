import java.net.*;
import java.io.*;

public class VoiceChatClient{
    private static final int TCP_PORT = 5000;
    private static final int UDP_PORT = 5001;
    private static final String SERVER_IP = "127.0.0.1";
    private static final int CLIENT_ID = 123;

    private TcpControlChannel tcpChannel;
    private AudioHander audioHandler;

    private final AtomicBoolean isMute = new AtomicBoolean(false);

    public VoiceChatClient {
        try{
            tcpChannel = new TcpControlChannel(SERVER_IP, TCP_PORT, CLIENT_ID);

            audioHandler = new AudioHandler(UDP_PORT, SERVER_IP, CLIENT_ID);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void joinSession(){
        if(tcpChannel.connect()){
            System.out.println("Connect to TCP . Send join command....");
            tcpChannel.sendCommand("Join");

            audioHandler.startStreaming();
            audioHandler.startReceiving();
        }
        else{
            System.out.println("Cann't connect to TCP server");
        }
    }

    public void leaveSession(){
        if(tcpChannel.isConnected()){
            System.out.println("Send leave command...");
            tcpChannel.sendCommand("Leave");
            tcpChannel.disconnect();
        }
        audioHandler.stopStreaming();
        audioHandler.stopReceiving();
        System.out.println("Left the session.");
    }

    public void toggleMute(){
        boolean mute = !isMute.get();
        isMute.set(mute);
        String command = mute ? "Mute" : "Unmute";
        
        tcpChannel.sendCommand(command);
        System.out.println("Changed mute state to: " + (mute ? "Muted" : "Unmuted"));
    }

    public static void main(String[] args){
        VoiceChatClient client = new VoiceChatClient();
        client.joinSession();

        try{
            BufferedReader in =  new BufferedReader(new InputStreamReader(System,in));
            String command;
            while((command = in.readLine()) != null){
                if(command.equalsIgnoreCase("leave")){
                    client.leaveSession();
                    break;
                } else if(command.equalsIgnoreCase("mute")){
                    client.toggleMute();
                } else if(command.equalsIgnoreCase("unmute")){
                    client.toggleMute();
                } else {
                    System.out.println("Unknown command: " + command);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}