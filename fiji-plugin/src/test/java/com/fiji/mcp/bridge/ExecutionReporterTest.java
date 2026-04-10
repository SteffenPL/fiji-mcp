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
}
