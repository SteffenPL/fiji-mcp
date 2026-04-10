package com.fiji.mcp.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionReporterTest {

    private AtomicReference<String> mockLog;
    private ExecutionReporter reporter;

    @BeforeEach
    void setUp() {
        mockLog = new AtomicReference<>("");
        reporter = new ExecutionReporter(() -> mockLog.get(), () -> "test-image.tif");
    }

    @AfterEach
    void tearDown() {
        reporter.shutdown();
    }

    @Test
    void runReported_completedSyncReturnsCompletedEnvelope() {
        JsonObject result = reporter.runReported("macro", null, 60, () -> "the value");

        assertEquals("completed", result.get("status").getAsString());
        assertEquals("the value", result.get("value").getAsString());
        assertEquals("", result.get("stdout").getAsString());
        assertTrue(result.get("error").isJsonNull());
        assertTrue(result.get("execution_id").isJsonNull());
        assertTrue(result.get("duration_ms").getAsLong() >= 0);
        assertEquals("test-image.tif", result.get("active_image").getAsString());
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
