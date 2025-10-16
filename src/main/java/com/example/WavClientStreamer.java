package com.example;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility that behaves like a client: it opens a UDP socket, registers with the server over TCP,
 * receives the assigned client id, and streams the WAV file from the same UDP socket so the server
 * forwards the audio to other registered clients.
 *
 * Usage: WavClientStreamer <wavFile> [serverHost] [serverTcpPort] [serverUdpPort] [loop]
 */
public class WavClientStreamer {
    private static final Logger logger = Logger.getLogger(WavClientStreamer.class.getName());
    private static final int FRAME_BYTES = 320; // match AudioHandler frame size
    private static final float TARGET_RATE = 8000.0F;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: WavClientStreamer <wavFile> [serverHost] [serverTcpPort] [serverUdpPort] [loop]");
            return;
        }

        String wavPath = args[0];
        String serverHost = args.length > 1 ? args[1] : "127.0.0.1";
        int serverTcpPort = args.length > 2 ? Integer.parseInt(args[2]) : 4444;
        int serverUdpPort = args.length > 3 ? Integer.parseInt(args[3]) : 5555;
        boolean loop = args.length > 4 && Boolean.parseBoolean(args[4]);

        File wavFile = new File(wavPath);
        if (!wavFile.exists()) {
            System.err.println("WAV file not found: " + wavPath);
            return;
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            // Open TCP control channel and register our UDP port so server accepts packets from us
            TcpControlChannel control = new TcpControlChannel(serverHost, serverTcpPort, -1);
            if (!control.connect()) {
                System.err.println("Failed to connect to TCP control server at " + serverHost + ":" + serverTcpPort);
                return;
            }

            int assignedId = control.registerAndWait(socket.getLocalPort(), 3000);
            if (assignedId < 0) {
                System.err.println("Failed to register with server (no OK reply)");
                control.disconnect();
                return;
            }
            System.out.println("Registered with server, assigned clientId=" + assignedId + ", streaming from UDP port=" + socket.getLocalPort());

            // Stream WAV frames
            int seq = 0;
            do {
                try (AudioInputStream in = AudioSystem.getAudioInputStream(wavFile)) {
                    AudioFormat base = in.getFormat();
                    AudioFormat target = new AudioFormat(TARGET_RATE, 16, 1, true, false);

                    AudioInputStream ais = AudioSystem.isConversionSupported(target, base)
                            ? AudioSystem.getAudioInputStream(target, in)
                            : convertIfNeeded(in, target);

                    byte[] buf = new byte[FRAME_BYTES];
                    int bytesRead;
                    long lastSend = System.currentTimeMillis();
                    int frameMs = Math.max(1, (int) (FRAME_BYTES * 1000 / (TARGET_RATE * 2)));

                    InetAddress serverAddr = InetAddress.getByName(serverHost);

                    while ((bytesRead = ais.read(buf)) != -1) {
                        ByteBuffer header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
                        header.putInt(assignedId);
                        header.putInt(seq);
                        header.putShort((short) bytesRead);

                        byte[] packet = new byte[10 + bytesRead];
                        System.arraycopy(header.array(), 0, packet, 0, 10);
                        System.arraycopy(buf, 0, packet, 10, bytesRead);

                        DatagramPacket p = new DatagramPacket(packet, packet.length, serverAddr, serverUdpPort);
                        socket.send(p);

                        seq++;

                        long now = System.currentTimeMillis();
                        long elapsed = now - lastSend;
                        if (elapsed < frameMs) {
                            TimeUnit.MILLISECONDS.sleep(frameMs - elapsed);
                        }
                        lastSend = System.currentTimeMillis();
                    }
                } catch (UnsupportedAudioFileException | IOException e) {
                    logger.log(Level.SEVERE, "Error reading WAV: " + e.getMessage(), e);
                    break;
                }
            } while (loop);

            // Leave and cleanup
            control.sendCommand("LEAVE");
            control.disconnect();
            System.out.println("Finished streaming WAV.");
        }
    }

    private static AudioInputStream convertIfNeeded(AudioInputStream in, AudioFormat target) {
        try {
            return AudioSystem.getAudioInputStream(target, in);
        } catch (Exception e) {
            System.err.println("Could not convert audio stream to target format: " + e.getMessage());
            return in;
        }
    }
}
