# Audio-Streaming
Java-based multi-client audio streaming app using UDP for real-time audio and TCP for control. Users can join sessions, talk, and self-mute. A simple UDP-only demo is also provided.

## Features
- Real-time audio streaming using UDP
- Control commands via TCP
- Multi-client support
- Self-mute functionality
- Mute other users capability
- Session management

## Requirements
- JDK 8+ installed
- Microphone and speakers/headset
- Open firewall for the chosen UDP/TCP ports (when testing across machines)

## Installation
```bash
git clone https://github.com/yourusername/Audio-Streaming.git
cd Audio-Streaming
```

## Usage
This project uses a Swing UI and a server you can run directly from `App.java`:

- Start server (TCP: 4444, UDP: 5555 by default):
    - Run the app with argument `server`.
- Start client UI:
    - Run the app with argument `client`.
- Start both locally (ephemeral ports) for a quick demo:
    - Run the app with argument `local`.

See `src/main/java/com/audiostreaming/App.java` for details.

## Quick tutorial: local test
1) Start the server
     - In Terminal A:
         ```bash
         java AudioStreamingServer
         ```
     - Note the UDP/TCP ports it prints (or defaults).

2) Start the first client
     - In Terminal B, connect to the server (localhost for same machine):
         ```bash
         java AudioStreamingClient
         ```
     - If prompted, enter server host (localhost) and the ports. Otherwise pass them as arguments.

3) Start a second client
     - In Terminal C:
         ```bash
         java AudioStreamingClient
         ```
     - Join the same session as the first client.

4) Talk and verify audio
     - Speak into your microphone on one client and confirm you hear audio on the other.
     - Use the client’s self-mute control to verify you stop sending audio.
     - Use the “mute other user” control to verify you stop receiving specific users.


## Simple UDP-only demo
If you want the smallest possible demo (no presence, no mute, no control channel), use the examples:

1) In `Audio-Streaming/examples`:
    - Compile: `javac SimpleUdpRelayServer.java SimpleUdpClient.java`
    - Start server: `java SimpleUdpRelayServer 5555`
2) On each client machine:
    - Run: `java SimpleUdpClient <server_ip> 5555`

More info in `docs/SIMPLE_MODE.md`.

## Tips and troubleshooting
- Allow microphone permissions if prompted by your OS.
- If you hear nothing:
    - Verify both clients are in the same session and connected to the correct host/ports.
    - Check firewall rules on server and client machines.
    - Reduce other apps using the microphone or speakers.
- If audio is choppy:
    - Test on a wired network or reduce background network load.


