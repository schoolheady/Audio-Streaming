package com.example;

import org.junit.jupiter.api.Test;

import com.audiostreaming.AudioStreamingUI;
import com.audiostreaming.VoiceChatClient;

import javax.swing.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that disconnecting and reconnecting with a different username
 * doesn't show the old username in the participants list.
 */
public class UsernameChangeOnRejoinTest {

    static class StubClient extends VoiceChatClient {
        private Consumer<String> listener;

        public StubClient() {
            super();
        }

        @Override
        public void addServerMessageListener(Consumer<String> listener) {
            this.listener = listener;
        }

        @Override
        public void removeServerMessageListener(Consumer<String> listener) {
            this.listener = null;
        }

        @Override
        public void joinSession() {
            // No-op for tests
        }

        public Consumer<String> getListener() { return listener; }
        
        // Simulate server sending messages to this client
        public void simulateServerMessage(String message) {
            if (listener != null) {
                listener.accept(message);
            }
        }
    }

    @Test
    public void testUsernameChangeOnRejoinNoDuplicates() throws Exception {
        // 1. Create UI and simulate joining with "guest"
        AudioStreamingUI ui = new AudioStreamingUI();
        ui.initForTests();
        ui.setUsernameForTests("guest");
        
        StubClient client1 = new StubClient();
        ui.setClientForTests(client1, true);
        
        // Trigger join via UI (simulates user clicking Join button)
        SwingUtilities.invokeAndWait(() -> ui.callJoin());
        Thread.sleep(100);
        
        // Simulate server responses
        Consumer<String> listener1 = client1.getListener();
        assertNotNull(listener1, "Listener should be registered after join");
        
        // Simulate OK with ID 1
        SwingUtilities.invokeLater(() -> listener1.accept("OK 1"));
        Thread.sleep(100);
        
        // Verify "guest (You)" is in the list
        DefaultListModel<String> model = ui.getUserModelForTests();
        boolean hasGuestYou = false;
        for (int i = 0; i < model.size(); i++) {
            String entry = model.get(i);
            if (entry != null && entry.contains("guest") && entry.contains("(You)")) {
                hasGuestYou = true;
                break;
            }
        }
        assertTrue(hasGuestYou, "Should have 'guest (You)' in the list after initial join");
        
        // Simulate server sending presence for ourselves (should be ignored due to myAssignedId check)
        SwingUtilities.invokeLater(() -> listener1.accept("PRESENCE ADD 1 guest"));
        Thread.sleep(100);
        
        // Count entries with "guest" (should still be exactly 1 - no duplicate from PRESENCE ADD)
        long guestCount = countEntriesWithName(model, "guest");
        assertEquals(1, guestCount, "Should have exactly 1 entry with 'guest' (PRESENCE ADD for self should be ignored)");
        
        // 3. Disconnect (simulates user clicking Disconnect)
        SwingUtilities.invokeAndWait(() -> ui.callLeave());
        Thread.sleep(100);
        
        // 4. Change username and reconnect
        ui.setUsernameForTests("guest1");
        StubClient client2 = new StubClient();
        ui.setClientForTests(client2, true);
        ui.setJoinedForTests(false); // Reset joined flag
        
        // Trigger join with new username
        SwingUtilities.invokeAndWait(() -> ui.callJoin());
        Thread.sleep(100);
        
        Consumer<String> listener2 = client2.getListener();
        assertNotNull(listener2, "Listener should be registered after rejoin");
        
        // Simulate OK with same ID 1 (server reused the ID)
        SwingUtilities.invokeLater(() -> listener2.accept("OK 1"));
        Thread.sleep(100);
        
        // Simulate server sending presence for ourselves with new name (should be ignored)
        SwingUtilities.invokeLater(() -> listener2.accept("PRESENCE ADD 1 guest1"));
        Thread.sleep(100);
        
        // 5. Verify ONLY "guest1 (You)" appears, NOT "guest"
        boolean hasGuest1You = false;
        boolean hasOldGuest = false;
        
        for (int i = 0; i < model.size(); i++) {
            String entry = model.get(i);
            if (entry == null) continue;
            
            if (entry.contains("guest1") && entry.contains("(You)")) {
                hasGuest1You = true;
            }
            // Check for old "guest" entry (without guest1)
            if (entry.contains("guest") && !entry.contains("guest1")) {
                hasOldGuest = true;
            }
        }
        
        assertTrue(hasGuest1You, "Should have 'guest1 (You)' in the list after rejoin with new name");
        assertFalse(hasOldGuest, "Should NOT have old 'guest' entry in the list after rejoin with new name");
        
        // Count total entries with "guest" or "guest1" (should be exactly 1)
        long totalGuestEntries = 0;
        for (int i = 0; i < model.size(); i++) {
            String entry = model.get(i);
            if (entry != null && (entry.contains("guest") || entry.contains("guest1"))) {
                totalGuestEntries++;
            }
        }
        assertEquals(1, totalGuestEntries, "Should have exactly 1 entry total (no duplicates of old username)");
    }
    
    private long countEntriesWithName(DefaultListModel<String> model, String name) {
        long count = 0;
        for (int i = 0; i < model.size(); i++) {
            String entry = model.get(i);
            if (entry != null && entry.contains(name)) {
                count++;
            }
        }
        return count;
    }
}
