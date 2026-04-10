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
    private final ConcurrentHashMap<String, Slot> active = new ConcurrentHashMap<>();
    private volatile String currentId;

    public ExecutionReporter(Supplier<String> logSnapshot,
                             Supplier<String> activeImageTitle) {
        this.logSnapshot = logSnapshot;
        this.activeImageTitle = activeImageTitle;
    }

    public JsonObject runReported(String type, Integer softTimeoutSeconds,
                                  int hardTimeoutSeconds, Callable<Object> body) {
        String existing = currentId;
        if (existing != null && active.containsKey(existing)) {
            return buildExecutionInProgress(existing);
        }

        long startMillis = System.currentTimeMillis();
        String stdoutBefore = logSnapshot.get();
        String execId = "exec-" + counter.incrementAndGet();

        Callable<Object> wrapped = () -> {
            SourceTracker.setMcpActive(true);
            try {
                return body.call();
            } finally {
                SourceTracker.setMcpActive(false);
            }
        };

        Future<Object> future = worker.submit(wrapped);
        Slot slot = new Slot(execId, type, future, startMillis, stdoutBefore, hardTimeoutSeconds);
        slot.hardCancel = scheduler.schedule(
                () -> internalCancel(slot, CancelReason.HARD_TIMEOUT),
                hardTimeoutSeconds, TimeUnit.SECONDS);
        active.put(execId, slot);
        currentId = execId;

        return awaitOrRunning(slot, softTimeoutSeconds);
    }

    public JsonObject waitFor(String executionId, Integer softTimeoutSeconds) {
        Slot slot = active.get(executionId);
        if (slot == null) {
            return buildUnknownExecution(executionId);
        }
        return awaitOrRunning(slot, softTimeoutSeconds);
    }

    public JsonObject kill(String executionId) {
        String targetId;
        if (executionId == null) {
            targetId = currentId;
            if (targetId == null) {
                return killResult(false, null, "no execution active");
            }
        } else {
            targetId = executionId;
        }

        Slot slot = active.get(targetId);
        if (slot == null) {
            return killResult(false, null, "no such execution");
        }

        internalCancel(slot, CancelReason.USER_KILL);
        return killResult(true, targetId, null);
    }

    private JsonObject killResult(boolean killed, String target, String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("killed", killed);
        if (target != null) result.addProperty("target", target);
        if (reason != null) result.addProperty("reason", reason);
        return result;
    }

    public void shutdown() {
        worker.shutdownNow();
        scheduler.shutdownNow();
    }

    // ── core wait/build logic ─────────────────────────────────────────

    private JsonObject awaitOrRunning(Slot slot, Integer softTimeoutSeconds) {
        try {
            Object value;
            if (softTimeoutSeconds == null) {
                // Block until completion or hard-timeout cancellation.
                value = slot.future.get();
            } else {
                value = slot.future.get(softTimeoutSeconds, TimeUnit.SECONDS);
            }
            reap(slot);
            return buildCompleted(slot, value, null);
        } catch (TimeoutException e) {
            return buildRunning(slot);
        } catch (CancellationException e) {
            reap(slot);
            Throwable error = slot.cancelReason == CancelReason.HARD_TIMEOUT
                    ? new HardTimeoutException(slot.hardTimeoutSeconds)
                    : new KilledException();
            return buildCompleted(slot, null, error);
        } catch (ExecutionException e) {
            reap(slot);
            return buildCompleted(slot, null, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildCompleted(slot, null, e);
        }
    }

    private void internalCancel(Slot slot, CancelReason reason) {
        slot.cancelReason = reason;
        slot.future.cancel(true);
        try {
            ij.Macro.abort();
        } catch (NoClassDefFoundError | RuntimeException ignored) {
            // Macro.abort() requires the IJ runtime; in unit tests it is absent.
        }
    }

    private void reap(Slot slot) {
        if (slot.hardCancel != null) {
            slot.hardCancel.cancel(false);
        }
        active.remove(slot.id);
        if (slot.id.equals(currentId)) {
            currentId = null;
        }
    }

    // ── envelope construction ─────────────────────────────────────────

    private JsonObject buildCompleted(Slot slot, Object value, Throwable error) {
        JsonObject env = new JsonObject();
        env.addProperty("status", "completed");
        env.addProperty("stdout", diff(slot.stdoutBefore, logSnapshot.get()));
        env.add("value", value == null ? JsonNull.INSTANCE
                                       : new JsonPrimitive(String.valueOf(value)));
        env.add("error", error == null ? JsonNull.INSTANCE
                                       : buildError(error, slot.type));
        env.addProperty("duration_ms", System.currentTimeMillis() - slot.startMillis);
        env.add("execution_id", JsonNull.INSTANCE);
        env.addProperty("active_image", activeImageTitle.get());
        return env;
    }

    private JsonObject buildRunning(Slot slot) {
        JsonObject env = new JsonObject();
        env.addProperty("status", "running");
        env.addProperty("stdout", diff(slot.stdoutBefore, logSnapshot.get()));
        env.add("value", JsonNull.INSTANCE);
        env.add("error", JsonNull.INSTANCE);
        env.addProperty("duration_ms", System.currentTimeMillis() - slot.startMillis);
        env.addProperty("execution_id", slot.id);
        env.addProperty("active_image", activeImageTitle.get());
        return env;
    }

    private JsonObject buildExecutionInProgress(String busyId) {
        JsonObject env = new JsonObject();
        env.addProperty("status", "completed");
        env.addProperty("stdout", "");
        env.add("value", JsonNull.INSTANCE);
        JsonObject err = new JsonObject();
        err.addProperty("message",
                "Another execution is in progress: " + busyId
                + ". Call wait_for_execution or kill_execution first.");
        err.addProperty("type", "ExecutionInProgress");
        err.add("line", JsonNull.INSTANCE);
        env.add("error", err);
        env.addProperty("duration_ms", 0);
        env.add("execution_id", JsonNull.INSTANCE);
        env.addProperty("active_image", activeImageTitle.get());
        return env;
    }

    private JsonObject buildUnknownExecution(String requestedId) {
        JsonObject env = new JsonObject();
        env.addProperty("status", "completed");
        env.addProperty("stdout", "");
        env.add("value", JsonNull.INSTANCE);
        JsonObject err = new JsonObject();
        err.addProperty("message", "No execution with id " + requestedId);
        err.addProperty("type", "UnknownExecution");
        err.add("line", JsonNull.INSTANCE);
        env.add("error", err);
        env.addProperty("duration_ms", 0);
        env.add("execution_id", JsonNull.INSTANCE);
        env.addProperty("active_image", activeImageTitle.get());
        return env;
    }

    private static final java.util.regex.Pattern IJ_LINE_PATTERN =
            java.util.regex.Pattern.compile("in line (\\d+)");

    private JsonObject buildError(Throwable t, String typeHint) {
        JsonObject err = new JsonObject();
        String message = t.getMessage() == null ? t.getClass().getSimpleName()
                                                : t.getMessage();
        err.addProperty("message", message);
        err.addProperty("type", classifyType(t, typeHint));
        err.add("line", JsonNull.INSTANCE);

        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof javax.script.ScriptException sx && sx.getLineNumber() > 0) {
                err.addProperty("line", sx.getLineNumber());
                return err;
            }
            cur = cur.getCause();
        }

        java.util.regex.Matcher m = IJ_LINE_PATTERN.matcher(message);
        if (m.find()) {
            err.addProperty("line", Integer.parseInt(m.group(1)));
        }
        return err;
    }

    private String classifyType(Throwable t, String typeHint) {
        if (t instanceof KilledException) return "Killed";
        if (t instanceof HardTimeoutException) return "TimeoutError";
        return switch (typeHint) {
            case "macro"   -> "MacroError";
            case "script"  -> "ScriptError";
            case "command" -> "CommandError";
            default        -> "ExecutionError";
        };
    }

    private String diff(String before, String after) {
        if (after == null) return "";
        if (before == null) return after;
        if (after.startsWith(before)) return after.substring(before.length());
        return after;
    }

    // ── inner types ───────────────────────────────────────────────────

    private enum CancelReason { HARD_TIMEOUT, USER_KILL }

    private static class Slot {
        final String id;
        final String type;
        final Future<Object> future;
        final long startMillis;
        final String stdoutBefore;
        final int hardTimeoutSeconds;
        volatile ScheduledFuture<?> hardCancel;
        volatile CancelReason cancelReason;

        Slot(String id, String type, Future<Object> future,
             long startMillis, String stdoutBefore, int hardTimeoutSeconds) {
            this.id = id;
            this.type = type;
            this.future = future;
            this.startMillis = startMillis;
            this.stdoutBefore = stdoutBefore;
            this.hardTimeoutSeconds = hardTimeoutSeconds;
        }
    }

    static class KilledException extends RuntimeException {
        KilledException() { super("Execution killed by kill_execution request"); }
    }

    static class HardTimeoutException extends RuntimeException {
        HardTimeoutException(int seconds) {
            super("Execution exceeded hard timeout of " + seconds + "s");
        }
    }
}
