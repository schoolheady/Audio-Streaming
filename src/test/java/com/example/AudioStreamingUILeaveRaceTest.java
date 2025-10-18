package com.example;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class AudioStreamingUILeaveRaceTest {

    @Test
    public void leaveDoesNotCrashWhenServerMessageArrivesConcurrently() throws Exception {
        AudioStreamingUI ui = new AudioStreamingUI();
        ui.initForTests();

        // Stub client that captures the listener and allows us to invoke it
        class StubClient extends VoiceChatClient {
            Consumer<String> listener;
            @Override public void addServerMessageListener(java.util.function.Consumer<String> listener) { this.listener = listener; }
            @Override public void removeServerMessageListener(java.util.function.Consumer<String> listener) { this.listener = null; }
            @Override public void joinSession() {}
            @Override public void leaveSession() {}
            public Consumer<String> getListener() { return this.listener; }
        }

        StubClient stub = new StubClient();
        ui.setClientForTests(stub, true);
        ui.setUsernameForTests("RaceUser");

        // Join on EDT to create listener
        SwingUtilities.invokeAndWait(() -> ui.callJoin());

        Consumer<String> listener = stub.getListener();
        assertNotNull(listener, "listener should be registered after join");

        // Now simulate concurrent server messages during leave
        CountDownLatch start = new CountDownLatch(1);
        Thread t1 = new Thread(() -> {
            try {
                start.await();
                // simulate a PRESENCE ADD arriving while UI is leaving
                listener.accept("PRESENCE ADD 999 Intruder");
            } catch (Exception ignored) {}
        }, "race-producer");

        Thread t2 = new Thread(() -> {
            try {
                start.await();
                // call leave on EDT
                SwingUtilities.invokeAndWait(() -> ui.callLeave());
            } catch (Exception ignored) {}
        }, "leave-caller");

        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        // release both threads to run concurrently
        start.countDown();

        // wait briefly for operations to finish
        t1.join(1000);
        t2.join(1000);

        // After leave, UI model should be cleared and show Disconnected
        DefaultListModel<?> model = ui.getUserModelForTests();
        assertEquals(1, model.size());
        assertTrue(model.get(0).toString().contains("Disconnected"));
    }
}
