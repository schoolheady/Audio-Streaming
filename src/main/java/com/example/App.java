
/**
 * Simple App class to verify project skeleton.
 */
import java.net.*;

public class App {
    public static String greet(String name) {
        if (name == null || name.isBlank()) return "Hello, World!";
        return "Hello, " + name + "!";
    }

    public static void main(String[] args) throws SocketException, Exception {
        //System.out.println(greet(" "));
        DatagramSocket socket = new DatagramSocket(4445);
        Server server = new Server(socket);
        server.receiveThenSend();

    }
}
