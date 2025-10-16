import java.io.*;
import java.net.*;

public class TcpControlChannel{
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final String serverIp;
    private final int port;
    private final int clientId;

    public TcpControlChannel(String serverIp, int port, int clientId) throws IOException {
        this.serverIp = serverIp;
        this.port = port;
        this.clientId = clientId;
    }

    public boolean connect(){
        try{
            socket = new Socket(serverIp, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            System.out.println("Connected to TCP server at " + serverIp + ":" + port);
            return true;
        } catch (IOException e) {
            System.err.println("Failed to connect to TCP server: " + e.getMessage());
            return false;
        }
    }

    public void sendCommand(String command){
        if(out != null){
            out.println(command + " " + clientId);
            System.out.println("Sent TCP command: " + command);
        }
    }

    public void disconnect(){
        try{
            if (socket != null ){
                socket.close()
            }
        }catch (IOException e){
            System.err.println("Error closing TCP connection: " + e.getMessage());
        }
    }

    public boolean isConnected(){
        return socket != null && !socket.isClosed();
    }
}