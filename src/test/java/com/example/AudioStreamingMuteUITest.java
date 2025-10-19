package com.example;

import org.junit.jupiter.api.Test;

import com.audiostreaming.AudioStreamingUI;
import com.audiostreaming.VoiceChatClient;

import javax.swing.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class AudioStreamingMuteUITest {

    static class StubClient extends VoiceChatClient {
        private final AtomicBoolean toggled = new AtomicBoolean(false);
        public StubClient() { super(); }
        @Override
        public void toggleMute() { toggled.set(!toggled.get()); }
        public boolean wasToggled() { return toggled.get(); }
    }

    @Test
    public void toggleMuteUpdatesLabelAndCallsClient() throws Exception {
        AudioStreamingUI ui = new AudioStreamingUI();
        ui.initForTests();

        StubClient stub = new StubClient();
        ui.setClientForTests(stub, true);

        // Ensure initial state is muted (as in UI code)
        ui.setJoinedForTests(true);

        // Call toggleMute on EDT
        SwingUtilities.invokeAndWait(() -> ui.callJoin()); // ensure join listener exists
        SwingUtilities.invokeAndWait(() -> ui.callToggleMute());

        // Check client toggle called
        assertTrue(stub.wasToggled(), "Client.toggleMute should have been invoked");

    // Check label updated (not null)
    String labelText = ui.getMuteLabelTextForTests();
    assertNotNull(labelText);
    }

    // reflection helper
    private static Object getField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
