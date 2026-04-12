package com.fiji.mcp.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class DialogWatchdogTest {

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dialog-watchdog-test");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void newModalDialogIsDisposedAfterStart() throws Exception {
        List<DialogProbe> probes = new ArrayList<>();
        DialogWatchdog wd = new DialogWatchdog(() -> new ArrayList<>(probes), scheduler, 50, 20);
        wd.start();
        // After snapshot, add a new modal-and-visible probe.
        RecordingDialogProbe probe = new RecordingDialogProbe("oops", "Body text", true);
        probes.add(probe);

        waitForDispose(probe, 1000);

        wd.stop();
        assertTrue(probe.disposed, "expected probe.dispose() to have been called");
        List<DismissedDialog> dismissed = wd.dismissed();
        assertEquals(1, dismissed.size());
        assertEquals("oops", dismissed.get(0).title());
        assertEquals("Body text", dismissed.get(0).text());
    }

    @Test
    void preExistingModalDialogIsLeftAlone() throws Exception {
        RecordingDialogProbe preExisting = new RecordingDialogProbe("user-dialog", "", true);
        List<DialogProbe> probes = new ArrayList<>();
        probes.add(preExisting);
        DialogWatchdog wd = new DialogWatchdog(() -> new ArrayList<>(probes), scheduler, 50, 20);
        wd.start();   // snapshot includes preExisting

        // Give the poll loop time to run multiple iterations.
        Thread.sleep(300);
        wd.stop();

        assertFalse(preExisting.disposed, "pre-existing dialog must never be disposed");
        assertEquals(0, wd.dismissed().size());
    }

    @Test
    void nonModalOrInvisibleDialogIsLeftAlone() throws Exception {
        List<DialogProbe> probes = new ArrayList<>();
        DialogWatchdog wd = new DialogWatchdog(() -> new ArrayList<>(probes), scheduler, 50, 20);
        wd.start();

        RecordingDialogProbe nonModal = new RecordingDialogProbe("not-modal", "", false);
        probes.add(nonModal);

        Thread.sleep(300);
        wd.stop();

        assertFalse(nonModal.disposed, "non-modal-or-invisible dialog must not be disposed");
        assertEquals(0, wd.dismissed().size());
    }

    @Test
    void consecutiveStartCallsResetSnapshot() throws Exception {
        List<DialogProbe> probes = new ArrayList<>();
        RecordingDialogProbe d1 = new RecordingDialogProbe("first-cycle", "", true);
        probes.add(d1);

        DialogWatchdog wd = new DialogWatchdog(
                () -> new ArrayList<>(probes), scheduler, 50, 20);

        // First execution: d1 is in the snapshot, so it survives.
        wd.start();
        Thread.sleep(200);
        wd.stop();
        assertFalse(d1.disposed, "d1 was pre-existing in cycle 1");

        // Now d1 is no longer in the probe list (e.g. user dismissed it manually).
        // d2 appears as part of cycle 2's snapshot. d3 is the new offender.
        probes.clear();
        RecordingDialogProbe d2 = new RecordingDialogProbe("cycle2-pre", "", true);
        probes.add(d2);

        wd.start();   // snapshot now contains only d2
        RecordingDialogProbe d3 = new RecordingDialogProbe("cycle2-new", "", true);
        probes.add(d3);

        waitForDispose(d3, 1000);
        wd.stop();

        assertTrue(d3.disposed, "d3 should be dismissed in cycle 2");
        assertFalse(d2.disposed, "d2 was in cycle 2's snapshot");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static void waitForDispose(RecordingDialogProbe probe, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!probe.disposed && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    /**
     * Test fake — controllable title/text/modality, records dispose calls.
     */
    static class RecordingDialogProbe implements DialogProbe {
        private static final AtomicInteger ID = new AtomicInteger();
        private final int id = ID.incrementAndGet();
        private final String title;
        private final String text;
        volatile boolean modalAndVisible;
        volatile boolean disposed;

        RecordingDialogProbe(String title, String text, boolean modalAndVisible) {
            this.title = title;
            this.text = text;
            this.modalAndVisible = modalAndVisible;
        }

        @Override public Object key() { return id; }
        @Override public String title() { return title; }
        @Override public String text() { return text; }
        @Override public boolean isModalAndVisible() { return modalAndVisible; }
        @Override public void dispose() { disposed = true; modalAndVisible = false; }
    }
}
