# Simple mode vs full app

The full app has features that keep it reliable and user-friendly:

- TCP control channel for JOIN/LEAVE/MUTE/UNMUTE and presence list updates
- Heartbeats and cleanup when a client vanishes
- Late-join presence synchronization
- UI thread safety and lifecycle management
- Sequencing and buffering on the server to forward audio in order
- NAT-friendly behavior and reconnect logic

If you only need a bare‑minimum demo to hear audio between two machines on a LAN, you can use the tiny examples in `examples/`.

What the simple example omits:

- No identities, no presence list, no mute state, no heartbeats
- No ordering or jitter buffering
- No resilience for reconnects or NAT rebinding

## Try the minimal relay on Windows (cmd.exe)

1) Open a terminal in `Audio-Streaming/examples` and compile:

```
javac SimpleUdpRelayServer.java SimpleUdpClient.java
```

2) Start the relay server (choose a UDP port, e.g., 5555):

```
java SimpleUdpRelayServer 5555
```

3) On each client machine, run the minimal client pointing to the server IP and port:

```
java SimpleUdpClient <server_ip> 5555
```

Speak into the mic on one machine and you should hear audio on the other. Use Ctrl+C to stop.

When you're ready for a real experience (mute/presence/late-join/robustness), use the main app (`App.java` with the Swing UI) and the full server.
