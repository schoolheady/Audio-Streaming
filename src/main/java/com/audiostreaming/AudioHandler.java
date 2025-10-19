package com.audiostreaming;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import javax.sound.sampled.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles audio capture, encoding, transmission, reception, and playback.
 * <p>
 * Manages separate threads for sending captured audio to the server and
 * receiving/playing audio from other clients. Implements jitter buffering
 * for smooth playback.
 * </p>
 */
@SuppressWarnings("resource")
public class AudioHandler {
    /** The server's IP address for audio transmission. */
    private final InetAddress serverAddress;
    
    /** The UDP port number on the server for audio packets. */
    private final int serverUdpPort;
    
    /** The client ID assigned by the server. */
    private volatile int assignedClientId;
    
    /** Atomic boolean controlling the mute state of this client. */
    private final AtomicBoolean isMute;

    /** Logger for this class. */
    private static final Logger logger = Logger.getLogger(AudioHandler.class.getName());

    /** Audio sample rate in Hz. */
    private static final float SAMPLE_RATE = 8000.0F;
    
    /** Audio sample size in bits. */
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    
    /** Number of audio channels (1 = mono). */
    private static final int CHANNELS = 1;
    
    /** Whether audio samples are signed. */
    private static final boolean SIGNED = true;
    
    /** Whether audio data uses big-endian byte order. */
    private static final boolean BIG_ENDIAN = false;
    
    /** Size of audio buffer in bytes. */
    private static final int BUFFER_SIZE = 320;

    /** The audio format configuration. */
    private final AudioFormat format;
    
    /** Thread for sending audio. */
    private Thread sendThread;
    
    /** Thread for receiving audio packets. */
    private Thread receiveThread;
    
    /** Thread for playing back received audio. */
    private Thread playbackThread;
    
    /** Flag indicating whether audio streaming is active. */
    private volatile boolean isRunning = true;
    
    /** The UDP socket for sending and receiving audio packets. */
    private final DatagramSocket socket; 
    
    /** Jitter buffers for each client, storing audio packets ordered by sequence number. */
    private final ConcurrentHashMap<Integer, ConcurrentSkipListMap<Long, byte[]>> jitterBuffers = new ConcurrentHashMap<>();
    
    /** The audio output line for playback. */
    private final SourceDataLine speakers;

    /**
     * Constructs an AudioHandler with the specified parameters.
     * 
     * @param serverAddress the server's inet address
     * @param serverUdpPort the UDP port for audio transmission
     * @param socket the DatagramSocket for sending/receiving audio
     * @param initialClientId the client's assigned ID
     * @param isMute atomic boolean controlling mute state
     * @throws LineUnavailableException if audio line cannot be opened
     */
    public AudioHandler(InetAddress serverAddress, int serverUdpPort, DatagramSocket socket, int initialClientId, AtomicBoolean isMute) throws LineUnavailableException {
        this.serverAddress = serverAddress;
        this.serverUdpPort = serverUdpPort;
        this.socket = socket;
        this.assignedClientId = initialClientId;
        this.isMute = isMute;

        format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        speakers = (SourceDataLine) AudioSystem.getLine(info);
        speakers.open(format);
        speakers.start();
    }
    
    /**
     * Returns the local UDP port used by this audio handler.
     * 
     * @return the local port number
     */
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    /**
     * Sets the client ID assigned by the server.
     * 
     * @param id the client ID to assign
     */
    public void setAssignedClientId(int id) {
        this.assignedClientId = id;
    }

    /**
     * Starts the audio streaming thread to capture and send audio to the server.
     */
    public void startStreaming() {
        isRunning = true;
        sendThread = new Thread(this::audioSender, "AudioSender");
        sendThread.start();
    }

    /**
     * Starts the receiving and playback threads for incoming audio from other clients.
     */
    public void startReceiving() {
        receiveThread = new Thread(this::audioReceiver, "AudioReceiver");
        receiveThread.start();
        playbackThread = new Thread(this::audioPlayback, "AudioPlayer");
        playbackThread.start();
    }

    /**
     * Stops the audio streaming thread and waits for it to terminate.
     */
    public void stopStreaming() {
        isRunning = false;
        if (sendThread != null) {
            try {
                sendThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Stops the receiving and playback threads, closes audio resources, and closes the socket.
     */
    @SuppressWarnings("resource")
    public void stopReceiving() {
        isRunning = false;
        if (receiveThread != null) {
            try {
                receiveThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if(playbackThread != null){
             try {
                playbackThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            speakers.drain();
        } catch (IllegalStateException e) {
            logger.log(Level.FINE, "Speakers drain failed: " + e.getMessage(), e);
        }
        try {
            speakers.close();
        } catch (IllegalStateException e) {
            logger.log(Level.FINE, "Speakers close failed: " + e.getMessage(), e);
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * Captures audio from the microphone and sends it to the server.
     * <p>
     * Implements the UDP header structure with client ID, sequence number, and audio length.
     * Respects mute state and sends keepalive packets when muted.
     * </p>
     */
    private void audioSender() {
        DataLine.Info mic = new DataLine.Info(TargetDataLine.class, format);
        try (TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(mic)) {
                microphone.open(format);
                microphone.start();

                byte[] buffer = new byte[BUFFER_SIZE];
            // Sequence number is int (4 bytes) starting at 0
            int sequenceNumber = 0; 
            long lastSendTime = System.currentTimeMillis();

            while (isRunning) {
                final int FRAME_DURATION_MS = (int)(BUFFER_SIZE * 1000 / (SAMPLE_RATE * SAMPLE_SIZE_IN_BITS/8)); // ~20ms
                
                // Mute/Unmute logic check
                if (!isMute.get()) { 
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    
                    if (bytesRead > 0) {
                        // UDP Header: 4 bytes clientId (int), 4 bytes sequenceNumber (int), 2 bytes audioLength (short)
                        // Total 10 bytes header, must be BIG-ENDIAN
                        
                        // Use ByteBuffer to pack the header in Big-Endian
                        ByteBuffer headerBuffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
                        headerBuffer.putInt(assignedClientId); // 4 bytes clientId
                        headerBuffer.putInt(sequenceNumber); // 4 bytes sequenceNumber (int32)
                        headerBuffer.putShort((short) bytesRead); // 2 bytes audioLength (uint16)
                        
                        ByteArrayOutputStream packetData = new ByteArrayOutputStream();
                        packetData.write(headerBuffer.array());
                        packetData.write(buffer, 0, bytesRead); // Audio payload

                        byte[] datasend = packetData.toByteArray();
                        DatagramPacket packet = new DatagramPacket(datasend, datasend.length, serverAddress, serverUdpPort);
                        socket.send(packet);
                        
                        sequenceNumber = (sequenceNumber + 1); // Increment sequence number
                        lastSendTime = System.currentTimeMillis();
                    }
                } else {
                    // Send a packet every 15-30s if idle (Mute)
                    if (System.currentTimeMillis() - lastSendTime > 15000) { // 15 seconds
                         // Send a keepalive packet (header only, audioLength = 0)
                        ByteBuffer headerBuffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
                        headerBuffer.putInt(assignedClientId);
                        headerBuffer.putInt(sequenceNumber);
                        headerBuffer.putShort((short) 0); // audioLength = 0
                        
                        byte[] datasend = headerBuffer.array();
                        DatagramPacket packet = new DatagramPacket(datasend, datasend.length, serverAddress, serverUdpPort);
                        socket.send(packet);
                        
                        sequenceNumber = (sequenceNumber + 1);
                        lastSendTime = System.currentTimeMillis();
                        logger.fine("Sent NAT Keepalive packet.");
                    }
                    try {
                        TimeUnit.MILLISECONDS.sleep(FRAME_DURATION_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (LineUnavailableException | IllegalArgumentException e) {
            if (isRunning) {
                logger.log(Level.SEVERE, "AudioSender initialization error: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            if (isRunning) {
                logger.log(Level.SEVERE, "Error in AudioSender: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Receives audio packets from the server and stores them in jitter buffers.
     * <p>
     * Unpacks the 10-byte UDP header (client ID, sequence number, audio length)
     * and stores audio data in per-client jitter buffers ordered by sequence number.
     * </p>
     */
    private void audioReceiver() {
        byte[] buffer = new byte[65535]; 
        while (isRunning) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // Use the created socket

                    if (packet.getLength() < 10) { // Packet must have at least 10 bytes of header
                        logger.fine("Dropping malformed packet: length too small.");
                    continue; 
                }

                // Unpack the Big-Endian header
                ByteBuffer byteBuffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength())
                                                .order(ByteOrder.BIG_ENDIAN);
                
                int senderId = byteBuffer.getInt(); // 4 bytes clientId
                // Read int (4 bytes) and convert to Long to treat as unsigned int for sequence number
                long sequenceNumber = byteBuffer.getInt() & 0xFFFFFFFFL; 
                int audioLength = byteBuffer.getShort() & 0xFFFF; // 2 bytes audioLength (unsigned short)
                
                // Validate packet length
                if (audioLength > BUFFER_SIZE || audioLength < 0 || packet.getLength() != 10 + audioLength) {
                    logger.fine("Dropping malformed packet: invalid audioLength or packet size.");
                    continue;
                }
                
                // If audioLength = 0, ignore (might be a keepalive packet)
                if(audioLength == 0) continue;
                
                byte[] audioData = new byte[audioLength];
                byteBuffer.get(audioData); // Payload

                // Use ConcurrentSkipListMap for the Jitter Buffer (ordered by sequence number)
                jitterBuffers.putIfAbsent(senderId, new ConcurrentSkipListMap<>());
                ConcurrentSkipListMap<Long, byte[]> queue = jitterBuffers.get(senderId);
                
                if (queue.size() < 200) { // Cap Jitter Buffer size (e.g., 200 frames)
                    queue.put(sequenceNumber, audioData); 
                }
            } catch (InterruptedIOException ignore) {
                // Ignore timeouts/interrupts (covers SocketTimeoutException as it subclasses InterruptedIOException)
            } catch (IOException e) {
                if (isRunning) {
                    logger.log(Level.SEVERE, "I/O error in AudioReceiver: " + e.getMessage(), e);
                }
            } catch (RuntimeException e) {
                if (isRunning) {
                    logger.log(Level.SEVERE, "Error in AudioReceiver: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Retrieves audio packets from jitter buffers and plays them through speakers.
     * <p>
     * Implements jitter buffering by waiting for a threshold of packets before playback.
     * Processes packets in sequence number order for smooth audio output.
     * </p>
     */
    private void audioPlayback() {
        // ~20ms playback interval
        final int PLAYBACK_INTERVAL_MS = (int)(BUFFER_SIZE * 1000 / (SAMPLE_RATE * SAMPLE_SIZE_IN_BITS/8)); 
        final int JITTER_THRESHOLD = 2; // Start playing when at least 2 packets are in the buffer
        
        while (isRunning) {
            boolean played = false;

            // Iterate through all client buffers
            for (ConcurrentSkipListMap<Long, byte[]> buffer : jitterBuffers.values()) {
                try {
                    // Jitter Threshold: only play if there are enough packets
                    if (buffer.size() >= JITTER_THRESHOLD) {
                        // Get and remove the packet with the lowest sequenceNumber (the oldest)
                        Long oldestKey = buffer.firstKey();
                        byte[] audioData = buffer.remove(oldestKey); 
                        
                        if (audioData != null) {
                            try {
                                speakers.write(audioData, 0, audioData.length);
                                played = true;
                            } catch (IllegalStateException | IllegalArgumentException e) {
                                logger.log(Level.WARNING, "Failed to write to speakers: " + e.getMessage(), e);
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Error in AudioPlayer: " + e.getMessage(), e);
                }
            }
            
            // Sleep to regulate playback speed
            if (!played) { 
                try {
                    // Sleep for the frame duration if nothing was played
                    TimeUnit.MILLISECONDS.sleep(PLAYBACK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        speakers.stop();
        speakers.close();
    }
}