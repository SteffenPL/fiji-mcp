package com.fiji.mcp.bridge;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class ExecutionReporter {

    private final Supplier<String> logSnapshot;
    private final Supplier<String> activeImageTitle;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fiji-mcp-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fiji-mcp-hard-timeout");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong counter = new AtomicLong(0);

    public ExecutionReporter(Supplier<String> logSnapshot,
                             Supplier<String> activeImageTitle) {
        this.logSnapshot = logSnapshot;
        this.activeImageTitle = activeImageTitle;
    }

    public JsonObject runReported(String type, Integer softTimeoutSeconds,
                                  int hardTimeoutSeconds, Callable<Object> body) {
        long startMillis = System.currentTimeMillis();
        String stdoutBefore = logSnapshot.get();

        Callable<Object> wrapped = () -> {
            SourceTracker.setMcpActive(true);
            try {
                return body.call();
            } finally {
                SourceTracker.setMcpActive(false);
            }
        };

        Future<Object> future = worker.submit(wrapped);
        long waitSeconds = softTimeoutSeconds != null ? softTimeoutSeconds : hardTimeoutSeconds;

        try {
            Object value = future.get(waitSeconds, TimeUnit.SECONDS);
            return buildCompleted(type, value, null, stdoutBefore, startMillis);
        } catch (ExecutionException e) {
            return buildCompleted(type, null, e.getCause(), stdoutBefore, startMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildCompleted(type, null, e, stdoutBefore, startMillis);
        } catch (TimeoutException e) {
            // Will be expanded in Task 4 to differentiate soft (running) vs hard (TimeoutError).
            return buildCompleted(type, null, e, stdoutBefore, startMillis);
        }
    }

    public void shutdown() {
        worker.shutdownNow();
        scheduler.shutdownNow();
    }

    // ── envelope construction ─────────────────────────────────────────

    private JsonObject buildCompleted(String type, Object value, Throwable error,
                                      String stdoutBefore, long startMillis) {
        JsonObject env = new JsonObject();
        env.addProperty("status", "completed");
        env.addProperty("stdout", diff(stdoutBefore, logSnapshot.get()));
        env.add("value", value == null ? JsonNull.INSTANCE
                                       : new JsonPrimitive(String.valueOf(value)));
        env.add("error", error == null ? JsonNull.INSTANCE
                                       : buildError(error, type));
        env.addProperty("duration_ms", System.currentTimeMillis() - startMillis);
        env.add("execution_id", JsonNull.INSTANCE);
        env.addProperty("active_image", activeImageTitle.get());
        return env;
    }

    private JsonObject buildError(Throwable t, String typeHint) {
        JsonObject err = new JsonObject();
        err.addProperty("message", t.getMessage() == null ? t.getClass().getSimpleName()
                                                          : t.getMessage());
        err.addProperty("type", t.getClass().getSimpleName());
        err.add("line", JsonNull.INSTANCE);
        return err;
    }

    private String diff(String before, String after) {
        if (after == null) return "";
        if (before == null) return after;
        if (after.startsWith(before)) return after.substring(before.length());
        return after;
    }
}
