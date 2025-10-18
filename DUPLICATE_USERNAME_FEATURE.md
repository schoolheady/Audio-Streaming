# Duplicate Username and Presence Tracking Improvements

## Summary of Changes

### 1. Duplicate Username Handling (Server.java)
**Problem**: Multiple users could register with the same username, causing confusion in the participant list.

**Solution**: The server now automatically detects duplicate usernames during registration and appends a unique suffix:
- First user: `Guest`
- Second user: `Guest#1`
- Third user: `Guest#2`
- And so on...

**Implementation** (lines 367-381 in Server.java):
```java
// Handle duplicate usernames: check if username already exists and append ID if needed
String finalUsername = username;
int nameCount = 0;
boolean nameExists = true;
while (nameExists) {
    nameExists = false;
    for (Map.Entry<Integer, ClientState> entry : clientStates.entrySet()) {
        if (entry.getKey().equals(clientId)) continue; // skip self
        ClientState cs = entry.getValue();
        if (cs != null && cs.username != null && cs.username.equals(finalUsername)) {
            nameExists = true;
            nameCount++;
            finalUsername = username + "#" + nameCount;
            break;
        }
    }
}
```

### 2. Accurate Presence Tracking (Server.java)
**Problem**: When a client disconnected abruptly (TCP connection closed), the server would mark them as DISCONNECTED but wouldn't send a PRESENCE REMOVE message. This caused the UI to show disconnected users in the participant list.

**Solution**: The server now sends PRESENCE REMOVE when:
1. A client explicitly sends the LEAVE command (existing behavior)
2. A client's TCP connection closes while they are in the call (ACTIVE or MUTED status)

**Implementation** (lines 445-460 in Server.java):
```java
} finally {
    // cleanup on disconnect
    Integer removed = tcpSocketToClientId.remove(tcpSocket);
    if (removed != null) {
        ClientState st = clientStates.get(removed);
        if (st != null) {
            // Only send PRESENCE REMOVE if the client was previously in the call (ACTIVE or MUTED)
            // Don't send duplicate PRESENCE REMOVE if already LEFT
            boolean wasInCall = (st.status == ClientStatus.ACTIVE || st.status == ClientStatus.MUTED);
            st.status = ClientStatus.DISCONNECTED;
            if (wasInCall) {
                sendTcpMessageToAll("PRESENCE REMOVE " + removed);
            }
        }
    }
    try { tcpSocket.close(); } catch (IOException ignored) {}
    tcpClients.remove(tcpSocket);
    System.out.println("[TCP] - ControlHandler exiting for " + tcpSocket.getRemoteSocketAddress());
}
```

## Testing

### New Tests Added (PresenceIntegrationTest.java)

1. **`duplicateUsernamesGetUniqueSuffixes()`**
   - Registers three clients with the same username "Guest"
   - Verifies each gets a unique name variant
   - Confirms naming pattern: "Guest", "Guest#1", "Guest#2"

2. **`presenceRemoveOnTcpDisconnect()`**
   - Tests that PRESENCE REMOVE is sent when a client disconnects abruptly
   - Registers a client, makes them ACTIVE with JOIN command
   - Closes TCP connection without sending LEAVE
   - Verifies listener receives PRESENCE REMOVE message

All tests pass successfully! ✓

## Manual Testing Scenario

To test these improvements manually:

1. **Start the Server**:
   ```
   mvn clean package
   java -cp target\audio-streaming-1.0-SNAPSHOT.jar com.example.Server
   ```

2. **Launch Multiple Clients with Same Username**:
   - Open 3 instances of `AudioStreamingUI`
   - Set all usernames to "Guest"
   - Click "Connect" and "Join Call" on all three
   - **Expected**: Participant list shows "Guest", "Guest#1", "Guest#2"

3. **Test Presence Tracking**:
   - From one client, click "Leave Call"
   - **Expected**: That user disappears from the participant list on other clients
   - Close a client window without leaving first
   - **Expected**: That user also disappears from the participant list

## Benefits

1. **Clearer User Identification**: Users with the same name are now distinguishable
2. **Accurate Participant List**: The UI always shows who is actually in the call, not who has ever connected
3. **Robust Disconnection Handling**: Even if clients crash or lose connection, they are properly removed from the participant list
4. **No Breaking Changes**: Existing functionality remains intact; this is purely an enhancement

## Protocol Details

The TCP protocol now ensures:
- `REGISTER <udpPort> <username>` → Server may modify username if duplicate detected
- `PRESENCE ADD <clientId> <finalUsername>` → Broadcasts the potentially modified username
- `PRESENCE REMOVE <clientId>` → Sent on both explicit LEAVE and TCP disconnection (if ACTIVE/MUTED)
