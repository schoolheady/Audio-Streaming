package com.audiostreaming;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.LineUnavailableException;

public class VoiceChatClient {

    private String serverIp = "127.0.0.1";
    private DatagramSocket udpSocket;
    private AudioHandler audioHandler;

    private int SERVER_TCP_PORT = 4444;
    private final int SERVER_UDP_PORT = 5555; // or pass as constructor arg

    private int RESPONSE_TIMEOUT_MS = 5000;
    private int udpPort;
    private int assignedClientId;
    private Socket tcpSocket;
    

    private BufferedReader in;
    private PrintWriter out;
    public VoiceChatClient() throws SocketException, IOException, LineUnavailableException {
        udpSocket = new DatagramSocket(0);
        udpPort = udpSocket.getLocalPort();
        audioHandler = new AudioHandler(InetAddress.getByName(serverIp), SERVER_UDP_PORT, udpSocket, 0, new AtomicBoolean(false));
    }


    public boolean connect() throws IOException {
        tcpSocket = new Socket(serverIp, SERVER_TCP_PORT);
        in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
        out = new PrintWriter(tcpSocket.getOutputStream(), true);
        return true;
    }

    public int register(String username) throws IOException {
        String uname = (username == null) ? "Guest" : username.replaceAll("\\s+", "_");
        this.assignedClientId = sendCommands("REGISTER " + udpPort + " " + uname, "OK");
        return this.assignedClientId;
    }

    public int changeUsername(String newUsername) throws IOException {
        String uname = newUsername.replaceAll("\\s+", "_");
        return sendCommands("CHANGE_USERNAME " + uname, "USERNAME_OK");
    }

    public int ping() throws IOException {
        return sendCommands("PING", "PONG");
    }

    public void startPingThread() {
        Thread pingThread = new Thread(() -> {
            while (true) {
                try {
                    int ret = ping();
                    if (ret < 0) {
                        System.out.println("Ping failed. Server may be unreachable.");
                        leaveSession();
                        break;
                    }
                    Thread.sleep(5000);
                } catch (IOException | InterruptedException e) {
                    System.err.println("Error in ping thread: " + e.getMessage());
                    break;
                }
            }
        });
        pingThread.setDaemon(true);
        pingThread.start();
    }

    public void joinSession() throws IOException {
         int ret = sendCommands("JOIN", "JOIN_OK");
         startPingThread();
         System.out.println("Joined session with client ID: " + assignedClientId);
         if(ret >= -1) {
             audioHandler.startStreaming(this.assignedClientId);
             audioHandler.startReceiving();
         } else {
             System.out.println("Failed to join session.");
         }
         
    }
    public int leaveSession() throws IOException {
        int ret = sendCommands("LEAVE", "LEAVE_OK");
        if (ret >= -1) {
            audioHandler.stopStreaming();
            audioHandler.stopReceiving();
        }
        return ret;
    }
    public int mute() throws IOException {
        int ret = sendCommands("MUTE", "MUTE_OK");
        if (ret >= -1) {
            audioHandler.setMuted(true); 
        }
        return ret;
    }

    public int unmute() throws IOException {
        int ret = sendCommands("UNMUTE", "UNMUTE_OK");
        if (ret >= -1) {
            audioHandler.setMuted(false); 
        }
        return ret;
    }

public String[] getActiveUsers() throws IOException {
    out.println("LIST_USERS");
    out.flush();

    tcpSocket.setSoTimeout(RESPONSE_TIMEOUT_MS);
    try {
        String response = in.readLine();
        if (response == null) return new String[0];

        response = response.trim();
        System.out.println("Received response: " + response);

        String[] parts = response.split("\\s+");
        if (parts.length == 0) return new String[0];

        int idx = 0;
        if (parts[idx].equalsIgnoreCase("OK")) {
            idx++;
            if (idx >= parts.length) return new String[0];
        }
        String token = parts[idx].toUpperCase();
        if (token.equals("USERS") || token.equals("USER_LIST")) {
            int usersStart = idx + 1;
            if (usersStart >= parts.length) {
                return new String[0];
            }
            String[] users = new String[parts.length - usersStart];
            System.arraycopy(parts, usersStart, users, 0, users.length);
            return users;
        }

        System.out.println("Unexpected response: " + response);
        return new String[0];

    } catch (SocketTimeoutException e) {
        System.err.println("Response timeout for command: LIST_USERS");
        return new String[0];
    } finally {
        tcpSocket.setSoTimeout(0);
    }
}


    public void disconnect() throws IOException {
        int ret = sendCommands("LEAVE", "LEAVE_OK");
        try { if (tcpSocket != null) tcpSocket.close(); } catch (IOException ignored) {}
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        if (audioHandler != null) {
            audioHandler.stopStreaming();
            audioHandler.stopReceiving();
        }
    }



private int sendCommands(String command, String expected) throws IOException {
    out.println(command);
    out.flush();

    tcpSocket.setSoTimeout(RESPONSE_TIMEOUT_MS);
    try {
        String response = in.readLine();
        if (response == null) return -1;

        response = response.trim();
        System.out.println("Received response: " + response);

        String[] parts = response.split("\\s+");
        if (parts.length == 0) return -1;

        String respToken = parts[0].toUpperCase();
        String expectedToken = (expected == null) ? null : expected.toUpperCase();

        boolean success = false;

        if (expectedToken != null && respToken.equals(expectedToken)) {
            success = true;
        } else if (expectedToken != null) {

            if ("OK".equals(expectedToken) && (respToken.equals("OK") || respToken.endsWith("_OK"))) {
                success = true;
            }
        } else {
            String cmdRoot = command.split("\\s+")[0].toUpperCase();
            if (respToken.equals("OK") || respToken.equals(cmdRoot + "_OK") || respToken.endsWith("_OK") && respToken.startsWith(cmdRoot)) {
                success = true;
            }
        }

        if (!success) {
            System.out.println("Unexpected response: " + response);
            return -1;
        }

        if (parts.length > 1) {
            try {
                int id = Integer.parseInt(parts[1]);
                if (respToken.startsWith("REGISTER") || respToken.equals("REGISTER_OK")) {
                    this.assignedClientId = id;
                }
                return id;
            } catch (NumberFormatException ignored) {
               }
        }

        return 0;

    } catch (SocketTimeoutException e) {
        System.err.println("Response timeout for command: " + command);
        return -1;
    } finally {
        tcpSocket.setSoTimeout(0);
    }
}

}

