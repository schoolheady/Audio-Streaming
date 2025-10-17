package com.example;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class AudioStreamingUITest {

    // Helper to get private fields via reflection
    private static Object getField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    static class StubClient extends VoiceChatClient {
        private Consumer<String> listener;
        private final AtomicBoolean removed = new AtomicBoolean(false);

        public StubClient() {
            super();
        }

        @Override
        public void addServerMessageListener(Consumer<String> listener) {
            this.listener = listener;
        }

        @Override
        public void removeServerMessageListener(Consumer<String> listener) {
            // mark removed when the same listener reference is passed or null is passed
            if (this.listener == listener || listener == null) removed.set(true);
            this.listener = null;
        }

        @Override
        public void joinSession() {
            // No-op for tests
        }

        @Override
        public void leaveSession() {
            // No-op for tests
        }

        public Consumer<String> getListener() { return listener; }
        public boolean wasRemoved() { return removed.get(); }
    }

    @Test
    public void joinAddsYouAndPresenceAddUpdatesList() throws Exception {
        AudioStreamingUI ui = new AudioStreamingUI();
        ui.initForTests();

        // Prepare: inject stub client and mark connected=true
        StubClient stub = new StubClient();
        ui.setClientForTests(stub, true);

        // Set username field to a known value
        ui.setUsernameForTests("TestUser");

        // Call joinCall on EDT to mimic user action
        SwingUtilities.invokeAndWait(() -> ui.callJoin());

        // After join, userModel should contain "TestUser (You)"
    DefaultListModel<?> model = ui.getUserModelForTests();
        boolean foundYou = false;
        for (int i = 0; i < model.size(); i++) {
            Object v = model.get(i);
            if (v != null && v.toString().contains("TestUser (You)")) foundYou = true;
        }
        assertTrue(foundYou, "UI should show the joined user as You");

        // Now simulate a PRESENCE ADD message delivered by server via the listener
    Consumer<String> serverListener = stub.getListener();
        assertNotNull(serverListener, "Stub client should have received the server listener");

        // Send PRESENCE ADD for id=2 name=Bob
    SwingUtilities.invokeAndWait(() -> serverListener.accept("PRESENCE ADD 2 Bob"));
        // Flush any pending EDT tasks (the listener uses invokeLater internally)
    SwingUtilities.invokeAndWait(() -> {});
        boolean foundBob = false;
        for (int i = 0; i < model.size(); i++) {
            Object v = model.get(i);
            if (v != null && v.toString().equals("Bob")) foundBob = true;
        }
        assertTrue(foundBob, "UI should add Bob when PRESENCE ADD arrives");
    }

    @Test
    public void leaveRemovesListenerAndClearsList() throws Exception {
        AudioStreamingUI ui = new AudioStreamingUI();
        ui.initForTests();

        StubClient stub = new StubClient();
        ui.setClientForTests(stub, true);

        // Simulate joined state and populate some entries
        DefaultListModel<String> model = new DefaultListModel<>();
        model.addElement("Alice (id=10)");
        model.addElement("TestUser (You)");
    ui.setUserModelForTests(model);

        // also ensure uiServerListener is set so removal happens; call joinCall to create it
        SwingUtilities.invokeAndWait(() -> ui.callJoin());

        // Call leaveServer
        SwingUtilities.invokeAndWait(() -> ui.callLeave());

        // After leave, model should contain only Disconnected
    DefaultListModel<?> after = ui.getUserModelForTests();
        assertEquals(1, after.size());
        assertTrue(after.get(0).toString().contains("Disconnected"));

        // And the stub client should have had its listener removed
        assertTrue(stub.wasRemoved(), "Client listener should be removed on leave");
    }
}
