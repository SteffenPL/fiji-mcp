package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionReporterTest {

    private AtomicReference<String> mockLog;
    private StderrTeeStream stderrTee;
    private PrintStream originalErr;
    private ExecutionReporter reporter;

    @BeforeEach
    void setUp() {
        mockLog = new AtomicReference<>("");
        originalErr = System.err;
        stderrTee = new StderrTeeStream(
                new PrintStream(new ByteArrayOutputStream(), true));
        // Worker bodies in these tests write to System.err — install the tee
        // as the JVM's stderr so the tee's capture path actually engages.
        System.setErr(stderrTee);
        reporter = new ExecutionReporter(
                () -> mockLog.get(), () -> "test-image.tif", stderrTee);
    }

    @AfterEach
    void tearDown() {
        reporter.shutdown();
        System.setErr(originalErr);
    }

    @Test
    void runReported_completedSyncReturnsCompletedEnvelope() {
        JsonObject result = reporter.runReported("macro", null, 60, () -> "the value");

        assertEquals("completed", result.get("status").getAsString());
        assertEquals("the value", result.get("value").getAsString());
        assertEquals("", result.get("stdout").getAsString());
        assertEquals("", result.get("stderr").getAsString());
        assertTrue(result.get("error").isJsonNull());
        assertTrue(result.get("execution_id").isJsonNull());
        assertTrue(result.get("duration_ms").getAsLong() >= 0);
        assertEquals("test-image.tif", result.get("active_image").getAsString());
    }

    @Test
    void runReported_capturesStderrWrittenByWorkerBody() {
        // fm-50jy: NPEs caught by IJ.handleException are dumped to System.err
        // and were swallowed before this fix. Verify worker-thread stderr ends
        // up on the envelope.
        JsonObject result = reporter.runReported("macro", null, 60, () -> {
            System.err.println("oops fm-50jy");
            return "ok";
        });

        assertEquals("completed", result.get("status").getAsString());
        assertEquals("ok", result.get("value").getAsString());
        assertTrue(result.get("stderr").getAsString().contains("oops fm-50jy"),
                "expected stderr in envelope, got: " + result.get("stderr"));
    }

    @Test
    void runReported_capturesStderrWhenBodyThrows() {
        // Even when the worker body throws, the finally block must still
        // hand off whatever stderr it produced before the exception.
        JsonObject result = reporter.runReported("macro", null, 60, () -> {
            System.err.println("trace before boom");
            throw new RuntimeException("boom");
        });

        assertEquals("trace before boom" + System.lineSeparator(),
                result.get("stderr").getAsString());
        assertEquals("boom",
                result.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    void runReported_capturesStderrFromSubThreadDuringWindow() throws Exception {
        // Regression for the live-test miss: SciJava ScriptService runs scripts
        // on its own thread pool, so the worker body for run_script blocks in
        // future.get() while the script body — and any IJ.handleException trace
        // it triggers — runs on a SciJava thread. Capture must not be keyed
        // on the worker thread alone.
        JsonObject result = reporter.runReported("script", null, 60, () -> {
            Thread sub = new Thread(() -> System.err.println("from sub-thread"));
            sub.start();
            sub.join();
            return "ok";
        });

        assertEquals("ok", result.get("value").getAsString());
        assertTrue(result.get("stderr").getAsString().contains("from sub-thread"),
                "expected sub-thread stderr to be captured, got: "
                + result.get("stderr"));
    }

    @Test
    void runReported_consecutiveCapturesDoNotBleedAcrossExecutions() {
        JsonObject first = reporter.runReported("macro", null, 60, () -> {
            System.err.println("first execution");
            return null;
        });
        JsonObject second = reporter.runReported("macro", null, 60, () -> {
            System.err.println("second execution");
            return null;
        });

        assertTrue(first.get("stderr").getAsString().contains("first execution"));
        assertFalse(first.get("stderr").getAsString().contains("second execution"));
        assertTrue(second.get("stderr").getAsString().contains("second execution"));
        assertFalse(second.get("stderr").getAsString().contains("first execution"));
    }

    @Test
    void runReported_capturesStdoutFromLogDiff() {
        JsonObject result = reporter.runReported("macro", null, 60, () -> {
            mockLog.set("hello\nworld\n");
            return null;
        });

        assertEquals("hello\nworld\n", result.get("stdout").getAsString());
        assertTrue(result.get("value").isJsonNull());
    }

    @Test
    void runReported_callableThrowingProducesErrorEnvelope() {
        JsonObject result = reporter.runReported("macro", null, 60, () -> {
            mockLog.set("started\n");
            throw new RuntimeException("boom");
        });

        assertEquals("completed", result.get("status").getAsString());
        assertEquals("started\n", result.get("stdout").getAsString());
        assertTrue(result.get("value").isJsonNull());

        JsonObject err = result.getAsJsonObject("error");
        assertEquals("boom", err.get("message").getAsString());
        assertEquals("MacroError", err.get("type").getAsString());
        assertTrue(err.get("line").isJsonNull());
    }

    @Test
    void runReported_extractsLineFromIjMacroErrorMessage() {
        JsonObject result = reporter.runReported("macro", null, 60, () -> {
            throw new RuntimeException("Undefined identifier in line 12: foo");
        });

        JsonObject err = result.getAsJsonObject("error");
        assertEquals(12, err.get("line").getAsInt());
    }

    @Test
    void runReported_softTimeoutReturnsRunningEnvelope() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        JsonObject result = reporter.runReported("macro", 1, 60, () -> {
            mockLog.set("partial output\n");
            release.await();
            return "final value";
        });

        assertEquals("running", result.get("status").getAsString());
        assertEquals("partial output\n", result.get("stdout").getAsString());
        assertTrue(result.get("value").isJsonNull());
        assertTrue(result.get("error").isJsonNull());
        String execId = result.get("execution_id").getAsString();
        assertNotNull(execId);
        assertTrue(execId.startsWith("exec-"));

        release.countDown();
    }

    @Test
    void runReported_hardTimeoutWithoutSoftReturnsTimeoutError() throws Exception {
        JsonObject result = reporter.runReported("macro", null, 1, () -> {
            mockLog.set("partial\n");
            Thread.sleep(10_000);
            return null;
        });

        assertEquals("completed", result.get("status").getAsString());
        JsonObject err = result.getAsJsonObject("error");
        assertEquals("TimeoutError", err.get("type").getAsString());
        assertEquals("partial\n", result.get("stdout").getAsString());
    }

    @Test
    void runReported_completionBeforeHardTimeoutCancelsTheScheduledKill() {
        JsonObject result = reporter.runReported("macro", null, 30, () -> "done");
        assertEquals("completed", result.get("status").getAsString());
        assertEquals("done", result.get("value").getAsString());
        // No assertion on the scheduler directly — but if the scheduled cancellation
        // wasn't itself cancelled it would later interfere with subsequent runs.
        JsonObject second = reporter.runReported("macro", null, 30, () -> "again");
        assertEquals("again", second.get("value").getAsString());
    }

    @Test
    void waitFor_returnsCompletedEnvelopeOnceWorkerFinishes() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        JsonObject running = reporter.runReported("macro", 1, 60, () -> {
            release.await();
            mockLog.set("done\n");
            return "final value";
        });
        String execId = running.get("execution_id").getAsString();

        release.countDown();

        JsonObject completed = reporter.waitFor(execId, null);
        assertEquals("completed", completed.get("status").getAsString());
        assertEquals("final value", completed.get("value").getAsString());
        assertEquals("done\n", completed.get("stdout").getAsString());
    }

    @Test
    void waitFor_unknownIdReturnsUnknownExecutionError() {
        JsonObject result = reporter.waitFor("exec-999", 1);
        assertEquals("completed", result.get("status").getAsString());
        JsonObject err = result.getAsJsonObject("error");
        assertEquals("UnknownExecution", err.get("type").getAsString());
    }

    @Test
    void waitFor_stillRunningReturnsAnotherRunningEnvelope() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        JsonObject first = reporter.runReported("macro", 1, 60, () -> {
            release.await();
            return null;
        });
        String execId = first.get("execution_id").getAsString();

        JsonObject second = reporter.waitFor(execId, 1);
        assertEquals("running", second.get("status").getAsString());
        assertEquals(execId, second.get("execution_id").getAsString());

        release.countDown();
    }

    @Test
    void kill_byIdInterruptsWorkerAndPropagatesKilledError() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        JsonObject running = reporter.runReported("macro", 1, 60, () -> {
            mockLog.set("started\n");
            started.countDown();
            Thread.sleep(60_000);
            return null;
        });
        String execId = running.get("execution_id").getAsString();
        started.await();

        JsonObject killResult = reporter.kill(execId);
        assertTrue(killResult.get("killed").getAsBoolean());
        assertEquals(execId, killResult.get("target").getAsString());

        JsonObject afterKill = reporter.waitFor(execId, null);
        assertEquals("completed", afterKill.get("status").getAsString());
        assertEquals("Killed", afterKill.getAsJsonObject("error").get("type").getAsString());
        assertEquals("started\n", afterKill.get("stdout").getAsString());
    }

    @Test
    void kill_withoutIdKillsCurrentSlot() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        JsonObject running = reporter.runReported("macro", 1, 60, () -> {
            started.countDown();
            Thread.sleep(60_000);
            return null;
        });
        String execId = running.get("execution_id").getAsString();
        started.await();

        JsonObject killResult = reporter.kill(null);
        assertTrue(killResult.get("killed").getAsBoolean());
        assertEquals(execId, killResult.get("target").getAsString());
    }

    @Test
    void kill_unknownIdReturnsNotKilled() {
        JsonObject killResult = reporter.kill("exec-999");
        assertFalse(killResult.get("killed").getAsBoolean());
        assertEquals("no such execution", killResult.get("reason").getAsString());
    }

    @Test
    void kill_emptySlotReturnsNotKilled() {
        JsonObject killResult = reporter.kill(null);
        assertFalse(killResult.get("killed").getAsBoolean());
        assertEquals("no execution active", killResult.get("reason").getAsString());
    }

    @Test
    void runReported_setsSourceTrackerOnWorkerThreadOnly() {
        java.util.concurrent.atomic.AtomicBoolean onWorker = new java.util.concurrent.atomic.AtomicBoolean(false);
        SourceTracker.setMcpActive(false);

        reporter.runReported("macro", null, 60, () -> {
            onWorker.set(SourceTracker.isMcpActive());
            return null;
        });

        assertTrue(onWorker.get(), "MCP active flag should be set on the worker thread");
        assertFalse(SourceTracker.isMcpActive(), "Calling thread must be unaffected");
    }

    @Test
    void runReported_secondCallWhileFirstActiveIsRejected() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        JsonObject first = reporter.runReported("macro", 1, 60, () -> {
            release.await();
            return null;
        });
        String firstId = first.get("execution_id").getAsString();
        assertEquals("running", first.get("status").getAsString());

        JsonObject second = reporter.runReported("macro", null, 60, () -> "ignored");
        assertEquals("completed", second.get("status").getAsString());
        JsonObject err = second.getAsJsonObject("error");
        assertEquals("ExecutionInProgress", err.get("type").getAsString());
        assertTrue(err.get("message").getAsString().contains(firstId));

        release.countDown();
    }

    @Test
    void runReported_resultsSnapshotIsNullWhenRowCountUnchanged() {
        JsonObject result = reporter.runReported("macro", null, 60, () -> "ok");
        assertTrue(result.has("results_snapshot"));
        assertTrue(result.get("results_snapshot").isJsonNull(),
                "results_snapshot should be null when table didn't change");
    }

    @Test
    void runReported_resultsSnapshotIncludedWhenRowCountChanges() {
        // Simulate a macro that adds rows to the results table.
        AtomicInteger rowCount = new AtomicInteger(0);
        JsonObject fakeSnapshot = new JsonObject();
        fakeSnapshot.addProperty("total_rows", 5);
        fakeSnapshot.add("columns", new JsonArray());
        fakeSnapshot.add("data", new JsonArray());

        ExecutionReporter custom = new ExecutionReporter(
                () -> "", () -> "test-image.tif", stderrTee,
                null, () -> {}, () -> {},
                rowCount::get,
                () -> fakeSnapshot);
        try {
            JsonObject result = custom.runReported("macro", null, 60, () -> {
                rowCount.set(5);  // simulate Analyze Particles populating the table
                return null;
            });

            assertFalse(result.get("results_snapshot").isJsonNull(),
                    "results_snapshot should be present when table changed");
            assertEquals(5,
                    result.getAsJsonObject("results_snapshot")
                          .get("total_rows").getAsInt());
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void runReported_envelopeAlwaysIncludesDismissedDialogsField() {
        JsonObject result = reporter.runReported("macro", null, 60, () -> "ok");
        assertTrue(result.has("dismissed_dialogs"),
                "every envelope must carry dismissed_dialogs[]");
        assertTrue(result.get("dismissed_dialogs").isJsonArray());
        assertEquals(0, result.getAsJsonArray("dismissed_dialogs").size());
    }

    @Test
    void runReported_promotesDialogDismissedErrorWhenScriptHadNoNaturalError() {
        // Use a fake watchdog factory that pre-records one dismissal.
        DialogWatchdog fake = new FakeWatchdog(java.util.Collections.singletonList(
                new DismissedDialog("No image", "There are no images open.", 100)));
        ExecutionReporter custom = new ExecutionReporter(
                () -> "", () -> "test-image.tif", stderrTee,
                () -> fake, () -> {}, () -> {});
        try {
            JsonObject result = custom.runReported("macro", null, 60, () -> "ok");

            assertTrue(result.has("error") && !result.get("error").isJsonNull());
            JsonObject err = result.getAsJsonObject("error");
            assertEquals("DialogDismissed", err.get("type").getAsString());
            assertTrue(err.get("message").getAsString().contains("No image"));

            assertEquals(1, result.getAsJsonArray("dismissed_dialogs").size());
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void runReported_preservesScriptErrorWhenDialogAlsoDismissed() {
        DialogWatchdog fake = new FakeWatchdog(java.util.Collections.singletonList(
                new DismissedDialog("oops", "", 50)));
        ExecutionReporter custom = new ExecutionReporter(
                () -> "", () -> "test-image.tif", stderrTee,
                () -> fake, () -> {}, () -> {});
        try {
            JsonObject result = custom.runReported("macro", null, 60, () -> {
                throw new RuntimeException("real script error");
            });

            JsonObject err = result.getAsJsonObject("error");
            // Real script error preserved, not overwritten by DialogDismissed.
            assertEquals("MacroError", err.get("type").getAsString());
            assertEquals("real script error", err.get("message").getAsString());
            // dismissed_dialogs still surfaced alongside.
            assertEquals(1, result.getAsJsonArray("dismissed_dialogs").size());
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void runReported_callsAcquireBeforeBodyAndReleaseAfter() {
        List<String> events = new ArrayList<>();
        Runnable acq = () -> events.add("acquire");
        Runnable rel = () -> events.add("release");
        ExecutionReporter custom = new ExecutionReporter(
                () -> "", () -> "test-image.tif", stderrTee, null, acq, rel);
        try {
            custom.runReported("macro", null, 60, () -> {
                events.add("body");
                return null;
            });
            assertEquals(java.util.Arrays.asList("acquire", "body", "release"), events);
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void runReported_releasesLockEvenWhenBodyThrows() {
        AtomicReference<String> released = new AtomicReference<>();
        ExecutionReporter custom = new ExecutionReporter(
                () -> "", () -> "test-image.tif", stderrTee,
                null, () -> {}, () -> released.set("released"));
        try {
            custom.runReported("macro", null, 60, () -> {
                throw new RuntimeException("boom");
            });
            assertEquals("released", released.get());
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void runReported_releasesLockEvenOnHardTimeout() {
        AtomicReference<String> released = new AtomicReference<>();
        ExecutionReporter custom = new ExecutionReporter(
                () -> "", () -> "test-image.tif", stderrTee,
                null, () -> {}, () -> released.set("released"));
        try {
            JsonObject result = custom.runReported("macro", null, 1, () -> {
                Thread.sleep(10_000);
                return null;
            });
            assertEquals("TimeoutError",
                    result.getAsJsonObject("error").get("type").getAsString());
            assertEquals("released", released.get());
        } finally {
            custom.shutdown();
        }
    }

    @Test
    void runReported_releasesLockEvenWhenAcquireThrew() {
        AtomicReference<String> released = new AtomicReference<>();
        Runnable badAcquire = () -> { throw new RuntimeException("acquire boom"); };
        Runnable goodRelease = () -> released.set("released");
        ExecutionReporter custom = new ExecutionReporter(
                () -> "", () -> "test-image.tif", stderrTee,
                null, badAcquire, goodRelease);
        try {
            JsonObject result = custom.runReported("macro", null, 60, () -> "ok");
            assertEquals("completed", result.get("status").getAsString());
            assertEquals("released", released.get());
        } finally {
            custom.shutdown();
        }
    }

    /**
     * Test fake — bypasses scheduling entirely. Pre-populated with the
     * dismissals you want the test to see.
     */
    private static class FakeWatchdog extends DialogWatchdog {
        private final List<DismissedDialog> preRecorded;

        FakeWatchdog(List<DismissedDialog> preRecorded) {
            super(java.util.Collections::emptyList,
                  java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                      Thread t = new Thread(r, "fake-wd");
                      t.setDaemon(true);
                      return t;
                  }),
                  60_000, 20);
            this.preRecorded = preRecorded;
        }

        @Override public synchronized void start() { /* no-op — we don't actually poll */ }
        @Override public void stop() { /* no-op */ }
        @Override public synchronized List<DismissedDialog> dismissed() {
            return new java.util.ArrayList<>(preRecorded);
        }
    }
}
