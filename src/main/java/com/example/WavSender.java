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

/**
 * Small utility to send a WAV file as properly formatted packets to the server UDP port.
 * Usage: WavSender <wavFile> [serverHost] [serverUdpPort] [clientId] [loop]
 * - wavFile: path to a PCM WAV file (will be converted to 8kHz/16bit/mono little-endian if needed)
 * - serverHost: default 127.0.0.1
 * - serverUdpPort: default 5555
 * - clientId: integer client id to include in packet header (defaults to 1)
 * - loop: if 'true', the file will be replayed in a loop
 */
public class WavSender {

    private static final int FRAME_BYTES = 320; // matches AudioHandler
    private static final float TARGET_RATE = 8000.0F;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: WavSender <wavFile> [serverHost] [serverUdpPort] [clientId] [loop]");
            return;
        }

        String wavPath = args[0];
        String serverHost = args.length > 1 ? args[1] : "127.0.0.1";
        int serverUdpPort = args.length > 2 ? Integer.parseInt(args[2]) : 5555;
        int clientId = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        boolean loop = args.length > 4 && Boolean.parseBoolean(args[4]);

        File wavFile = new File(wavPath);
        if (!wavFile.exists()) {
            System.err.println("WAV file not found: " + wavPath);
            return;
        }

        InetAddress serverAddr = InetAddress.getByName(serverHost);

        try (DatagramSocket socket = new DatagramSocket()) {
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
                    int frameMs = Math.max(1, (int) (FRAME_BYTES * 1000 / (TARGET_RATE * 2))); // bytes -> ms

                    while ((bytesRead = ais.read(buf)) != -1) {
                        // build header: 4 bytes clientId, 4 bytes sequenceNumber, 2 bytes audioLength (big-endian)
                        ByteBuffer header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
                        header.putInt(clientId);
                        header.putInt(seq);
                        header.putShort((short) bytesRead);

                        byte[] packet = new byte[10 + bytesRead];
                        System.arraycopy(header.array(), 0, packet, 0, 10);
                        System.arraycopy(buf, 0, packet, 10, bytesRead);

                        DatagramPacket p = new DatagramPacket(packet, packet.length, serverAddr, serverUdpPort);
                        socket.send(p);

                        seq++;

                        // pace to realtime
                        long now = System.currentTimeMillis();
                        long elapsed = now - lastSend;
                        if (elapsed < frameMs) {
                            TimeUnit.MILLISECONDS.sleep(frameMs - elapsed);
                        }
                        lastSend = System.currentTimeMillis();
                    }
                } catch (UnsupportedAudioFileException | IOException e) {
                    System.err.println("Error reading WAV: " + e.getMessage());
                    break;
                }
            } while (loop);
        }
    }

    // Helper fallback converter if AudioSystem.isConversionSupported returned false.
    private static AudioInputStream convertIfNeeded(AudioInputStream in, AudioFormat target) {
        // Try to convert via PCM conversion support; if not possible, return the original (may fail later)
        try {
            return AudioSystem.getAudioInputStream(target, in);
        } catch (Exception e) {
            System.err.println("Could not convert audio stream to target format: " + e.getMessage());
            return in;
        }
    }
}
