package com.fiji.mcp.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
}
