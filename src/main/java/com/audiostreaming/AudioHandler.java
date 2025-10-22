    package com.audiostreaming;
    import java.io.ByteArrayOutputStream;
    import java.io.InterruptedIOException;
    import java.net.*;
    import java.nio.ByteBuffer;
    import java.nio.ByteOrder;
    import java.util.concurrent.*;
    import java.util.concurrent.atomic.AtomicBoolean;
    import java.io.IOException;
    import javax.sound.sampled.*;
    import java.util.logging.Level;
    import java.util.logging.Logger;


    @SuppressWarnings("resource")
    public class AudioHandler {

        private final InetAddress serverAddress;
        private final int serverUdpPort;
        private volatile int assignedClientId;
        private final AtomicBoolean isMute;
        private static final Logger logger = Logger.getLogger(AudioHandler.class.getName());
        private static final float SAMPLE_RATE = 8000.0F;
        private static final int SAMPLE_SIZE_IN_BITS = 16;
        
        private static final int CHANNELS = 1;
        
        private static final boolean SIGNED = true;
        
        private static final boolean BIG_ENDIAN = false;
        
        private static final int BUFFER_SIZE = 320;

        private final AudioFormat format;
        
        private Thread sendThread;
        
        private Thread receiveThread;
        
        private Thread playbackThread;
        
        private volatile boolean isRunning = true;
        
        private final DatagramSocket socket; 
        
        private final ConcurrentHashMap<Integer, ConcurrentSkipListMap<Long, byte[]>> jitterBuffers = new ConcurrentHashMap<>();
        
        private final SourceDataLine speakers;


        public AudioHandler(InetAddress serverAddress, int serverUdpPort, DatagramSocket socket, int initialClientId, AtomicBoolean isMute) throws LineUnavailableException {
            this.serverAddress = serverAddress;
            this.serverUdpPort = serverUdpPort;
            this.socket = socket;
            //this.assignedClientId = initialClientId;
            this.isMute = isMute;

            format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            speakers = (SourceDataLine) AudioSystem.getLine(info);
            speakers.open(format);
            speakers.start();
        }
        

        public int getLocalPort() {
            return socket.getLocalPort();
        }

        public void setMuted(boolean mute) {
            isMute.set(mute);   
        }



        public void setAssignedClientId(int id) {
            this.assignedClientId = id;
        }


        public void startStreaming(int assignedClientId) {
            isRunning = true;
            sendThread = new Thread(() -> audioSender(assignedClientId), "AudioSender");
            sendThread.start();
        }

    
        public void startReceiving() {
            receiveThread = new Thread(this::audioReceiver, "AudioReceiver");
            receiveThread.start();
            playbackThread = new Thread(this::audioPlayback, "AudioPlayer");
            playbackThread.start();
        }


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

    
        private void audioSender(int assignedClientId) {
            System.out.println("AudioSender started.");
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
                            // print udpserver info
                            System.out.println("Audio packet sent to " + serverAddress.getHostAddress() + ":" + serverUdpPort);
                            //System.out.println("Sent audio packet: clientId=" + assignedClientId + " seq=" + (sequenceNumber - 1) + " len=" + bytesRead);
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
            System.out.println("AudioReceiver started.");
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
            System.out.println("AudioPlayer started.");
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