# Presence and Username Management Flow

## Scenario: Three Users Join with Same Username

```
Time  Client 1           Client 2           Client 3           Server State
----  ----------------   ----------------   ----------------   --------------------------
T1    Connect TCP        
      "REGISTER 5001 Guest"
                                                               clientStates[1] = {
                                                                 id: 1,
                                                                 username: "Guest",
                                                                 status: ACTIVE
                                                               }
                                                               
      Receives:                                                Broadcasts to all TCP clients:
      "OK 1"                                                   "PRESENCE ADD 1 Guest"
      "PRESENCE ADD 1 Guest"

T2                       Connect TCP
                         "REGISTER 5002 Guest"
                                                               Check: "Guest" exists
                                                               → Use "Guest#1"
                                                               
                                                               clientStates[2] = {
                                                                 id: 2,
                                                                 username: "Guest#1",
                                                                 status: ACTIVE
                                                               }
                                                               
      Receives:          Receives:                            Broadcasts to all:
      "PRESENCE ADD      "OK 2"                               "PRESENCE ADD 2 Guest#1"
       2 Guest#1"        "PRESENCE ADD 1 Guest"
                         "PRESENCE ADD 2 Guest#1"

T3                                          Connect TCP
                                            "REGISTER 5003 Guest"
                                                               Check: "Guest" exists
                                                               → Try "Guest#1", exists
                                                               → Use "Guest#2"
                                                               
                                                               clientStates[3] = {
                                                                 id: 3,
                                                                 username: "Guest#2",
                                                                 status: ACTIVE
                                                               }
                                                               
      Receives:          Receives:          Receives:         Broadcasts to all:
      "PRESENCE ADD      "PRESENCE ADD      "OK 3"            "PRESENCE ADD 3 Guest#2"
       3 Guest#2"        3 Guest#2"        "PRESENCE ADD 1 Guest"
                                           "PRESENCE ADD 2 Guest#1"
                                           "PRESENCE ADD 3 Guest#2"

T4                       Send "LEAVE"
                                                               clientStates[2].status = LEFT
                                                               
      Receives:          TCP preserved,     Receives:         Broadcasts to all:
      "PRESENCE REMOVE   audio stopped      "PRESENCE REMOVE  "PRESENCE REMOVE 2"
       2"                                    2"

T5    Close TCP
      (crash or exit)
                                                               TCP disconnects
                                                               Check: Client 1 was ACTIVE
                                                               → Send PRESENCE REMOVE
                                                               
                         Receives:          Receives:         Broadcasts to remaining:
                         "PRESENCE REMOVE   "PRESENCE REMOVE  "PRESENCE REMOVE 1"
                          1"                 1"

Final UI State on Client 3:
┌─────────────────────────┐
│ Participant List        │
├─────────────────────────┤
│ ● Guest#2 (You)        │
└─────────────────────────┘
```

## Key Points

1. **Username Deduplication**: Server checks existing usernames and appends suffix (#1, #2, etc.) automatically
2. **PRESENCE ADD**: Sent when a client registers; includes the final (possibly modified) username
3. **PRESENCE REMOVE**: Sent in two cases:
   - Explicit LEAVE command from client
   - TCP disconnection if client was ACTIVE or MUTED
4. **Status Tracking**: Server maintains ClientStatus (ACTIVE, MUTED, LEFT, DISCONNECTED)
5. **TCP Preservation**: LEAVE command keeps TCP open for quick rejoin; full disconnect closes everything

## UI Behavior

The `AudioStreamingUI` maintains two data structures:
- `idToName`: Map<Integer, String> - Maps server-assigned client ID to username
- `userModel`: DefaultListModel<String> - Swing list model for display

On receiving messages:
- `PRESENCE ADD <id> <name>`: Adds to both idToName and userModel (if not duplicate in display)
- `PRESENCE REMOVE <id>`: Removes from both structures using the ID to look up the name
- `joined` flag: Ensures late messages are ignored after leaving

This ensures the participant list always shows only connected, active participants.
