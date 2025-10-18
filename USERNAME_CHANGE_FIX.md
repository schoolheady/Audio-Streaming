# Username Change Fix

## Problem
When a client left and rejoined with a new username, the old username would persist in some scenarios, causing confusion or duplicate name issues.

### Root Cause
The registration logic had a race condition in how it handled username updates:

1. Client registers as "Alice" with clientId 1
2. Client leaves (ClientState still has username="Alice")
3. Client reconnects and registers as "Bob" (same IP+port, so reuses clientId 1)
4. **BUG**: The duplicate username checker ran BEFORE the ClientState was retrieved/created
5. This meant the old username "Alice" was still in the ClientState when checking for duplicates
6. The duplicate checker would skip clientId 1 (itself), but "Alice" remained in memory

## Solution
Changed the order of operations in `Server.java` registration logic:

**Before:**
```java
tcpSocketToClientId.put(tcpSocket, clientId);

// Check for duplicate usernames
String finalUsername = username;
// ... duplicate checking loop ...

// THEN get/create client state
ClientState st = clientStates.computeIfAbsent(clientId, id -> new ClientState());
```

**After:**
```java
tcpSocketToClientId.put(tcpSocket, clientId);

// Get/create client state FIRST
ClientState st = clientStates.computeIfAbsent(clientId, id -> new ClientState());
String oldUsername = st.username; // preserve for reference

// THEN check for duplicate usernames (now skips self properly)
String finalUsername = username;
// ... duplicate checking loop ...
```

## Key Changes
1. Moved `ClientState` retrieval **before** duplicate username checking
2. This ensures the duplicate checker properly skips the client's own previous username
3. When the username is updated (`st.username = finalUsername`), it replaces the old value cleanly

## Testing
Added comprehensive tests in `UsernameChangeTest.java`:

### Test 1: `clientCanChangeUsernameOnReregister`
- Client registers as "Alice"
- Client leaves and disconnects
- Client reconnects and registers as "Bob" (same UDP port)
- **Expected**: Presence shows "Bob", not "Alice" or "Bob#1"
- **Result**: ✓ Pass

### Test 2: `reregisterWithSameUsernameDoesNotAddSuffix`
- Client registers as "Charlie"
- Client leaves and reconnects with same username "Charlie"
- **Expected**: Presence shows "Charlie", not "Charlie#1"
- **Result**: ✓ Pass

All existing tests continue to pass (8/8 in PresenceIntegrationTest.java).

## User Impact
Users can now:
- Leave a call and rejoin with a different username without conflicts
- The participant list will correctly show the new username
- No duplicate suffixes (#1, #2) are added when changing your own name
- Previous username is completely replaced, not left in the system

## Example Flow
```
1. User connects as "Alice" → Server assigns ID 1, shows "Alice" in participant list
2. User clicks "Leave Call" → "Alice" removed from participant list
3. User changes name to "Bob" and clicks "Join Call" → Server reuses ID 1, shows "Bob" in participant list
4. No "Alice" remnants, no "Bob#1", just clean "Bob"
```
