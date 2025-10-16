import java.sound.sampled.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.nio.*;

public class AudioHander{
    private final int UDP_PORT;
    private final InetAdress serverAddress;
    private final int CLIENT_ID;
    private final AtomicBoolean isMute;

    private static final float SAMPLE_RATE = 8000.0F;
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final int CHANNELS = 1;
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;
    private static final int BUFFER_SIZE = 512;

    private AudioFormat audioFormat;
    private Thread sendThread , receiveThread;
    private volatile boolean isRunning = true;

    private final ConcurrentHashMap<Integer, BlockingQueue<byte[]>> jitterBuffers = new ConcurrentHashMap<>();
    private SourceDataLine speakers;

    public AudioHandler(InetAdress serverAddress , int UDP_PORT , int CLIENT_ID , AtomicBoolean isMute) throws LineUnvailibleException{
        this.UDP_PORT = UDP_PORT ;
        this.serverAddress = serverAddress;
        this.CLIENT_ID = CLIENT_ID;
        this.isMute = isMute;

        fomat = new AudioFormat(SAMPLE_RATE , SAMPLE_SIZE_IN_BITS , CHANNELS , SIGNED , BIG_ENDIAN);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class , format);
        speakers = (SourceDataLine) AudioSystem.getLine(info);
        speakers.open(format);
        speakers.start();
    }

    public void startStreaming(){
        isRunning = true;
        sendThread = new Thread(this::streamAudio, "AudioSender");
        sendThread.start();
    }

    public void startReceiving(){
       receiveThread = new Thread(this::audioReceiver, "AudioReceiver");
       receiveThread.start();
       new Thread(this::playAudio, "AudioPlayer").start();
    }

    public void stopStreaming(){
        isRunning = false;
        if(sendThread != null){
            try{
                sendThread.join();
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }
    }

    public void stopReceiving(){
        isRunning = false;
        if(receiveThread != null){
            try{
                receiveThread.join();
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }
        speakers.drain();
        speakers.close();
    }

    private void AudioSender(){
        try(DataGramSocket socket = new DataGramSocket()){
            DataLine.Info mic = new DataLine.Info(TargetDataLine.class , format);
            TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(mic);
            microphone.open(format);
            microphone.start();

            byte[] buffer = new byte[BUFFER_SIZE];
            long squenceNumber = 0;

            if(isMute){
                int bytesRead = microphone.read(buffer , 0 , buffer.length);
                if(bytesRead > 0){
                    ByteArrayOutputStream packetData = new ByteArrayOutputStream();
                    packetData.write(ByteBuffer.allocate(4).putInt(CLIENT_ID).array());
                    packetData.write(ByteBuffer.allocate(8).putLong(squenceNumber).array());
                    packetData.write(buffer , 0 , bytesRead);

                    byte[] datasend = packetData.toByteArray();
                    DatagramPacket packet = new DatagramPacket(datasend , datasend.length , serverAddress , UDP_PORT);
                    socket.send(packet);
                }
            }
            else{
                Thread.sleep(10);
            }
            microphone.stop();
            microphone.close();
        }catch(Exception e){
            if(isRunning){
                System.out.println("Error in AudioSender: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // Receive audio packets from server
    private void audioReceiver(){
        try(DataGramSocket socket = new DataGramSocket(UDP_PORT)){
            byte[] buffer = new byte[BUFFER_SIZE + 12]; // 4 bytes for clientId, 8 bytes for sequence number
            while(isRunning){
                DatagramPacket packet = new DatagramPacket(buffer , buffer.length);
                socket.receive(packet);

                ByteBuffer byteBuffer = ByteBuffer.wrap(packet.getData() , 0 , packet.getLength());
                int senderId = byteBuffer.getInt();
                long sequenceNumber = byteBuffer.getLong();
                byte[] audioData = new byte[packet.getLength() - 12];
                byteBuffer.get(audioData);

                jitterBuffers.putIfAbsent(senderId , new LinkedBlockingQueue<>());
                BlockingQueue<byte[]> queue = jitterBuffers.get(senderId);
                if(queue.size() < 50){ // Limit jitter buffer size
                    queue.offer(audioData);
                }
            }
        }catch(Exception e){
            if(isRunning){
                System.out.println("Error in AudioReceiver: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // Play audio from jitter buffers
    private void audioPlayback(){
        final int PLAYBACK_INTERVAL_MS = 20;
        
        while(isRunning){
            boolean plaing = false;

            for(BlockingQueue<byte[]>buffer : jitterBuffers.values()){
                try{
                    if(buffer.size( >= PLAYBACK_INTERVAL_MS || plaing)){
                        byte[] audioData = buffer.poll();
                        if(audioData != null){
                            speakers.write(audioData, 0 , audioData.length);
                            playing = true;
                        }
                    }
                }catch(Exception e){
                    System.out.println("Error in AudioPlayer: " + e.getMessage());
                }
            }
            if(!playing){
                try{
                    Thread.sleep(10);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    return;
                }
        }
        }
        speakers.stop();
        speakers.close();
    }
}