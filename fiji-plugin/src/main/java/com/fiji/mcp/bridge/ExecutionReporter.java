package com.fiji.mcp.bridge;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class ExecutionReporter {

    private final Supplier<String> logSnapshot;
    private final Supplier<String> activeImageTitle;
    private final StderrTeeStream stderrTee;
    private final Supplier<DialogWatchdog> watchdogFactory;
    private final Runnable lockAcquire;
    private final Runnable lockRelease;
    private final IntSupplier resultsRowCount;
    private final Supplier<JsonObject> resultsSnapshot;
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
                             Supplier<String> activeImageTitle,
                             StderrTeeStream stderrTee) {
        // Legacy/test shim — no watchdog, no lock, no results snapshot.
        this(logSnapshot, activeImageTitle, stderrTee,
             null, () -> {}, () -> {},
             () -> 0, () -> null);
    }

    public ExecutionReporter(Supplier<String> logSnapshot,
                             Supplier<String> activeImageTitle,
                             StderrTeeStream stderrTee,
                             Supplier<DialogWatchdog> watchdogFactory,
                             Runnable lockAcquire,
                             Runnable lockRelease) {
        this(logSnapshot, activeImageTitle, stderrTee,
             watchdogFactory, lockAcquire, lockRelease,
             () -> 0, () -> null);
    }

    public ExecutionReporter(Supplier<String> logSnapshot,
                             Supplier<String> activeImageTitle,
                             StderrTeeStream stderrTee,
                             Supplier<DialogWatchdog> watchdogFactory,
                             Runnable lockAcquire,
                             Runnable lockRelease,
                             IntSupplier resultsRowCount,
                             Supplier<JsonObject> resultsSnapshot) {
        this.logSnapshot = logSnapshot;
        this.activeImageTitle = activeImageTitle;
        this.stderrTee = stderrTee;
        this.watchdogFactory = watchdogFactory;
        this.lockAcquire = lockAcquire;
        this.lockRelease = lockRelease;
        this.resultsRowCount = resultsRowCount;
        this.resultsSnapshot = resultsSnapshot;
    }

    public JsonObject runReported(String type, Integer softTimeoutSeconds,
                                  int hardTimeoutSeconds, Callable<Object> body) {
        return runReported(type, softTimeoutSeconds, hardTimeoutSeconds, body, null);
    }

    public JsonObject runReported(String type, Integer softTimeoutSeconds,
                                  int hardTimeoutSeconds, Callable<Object> body,
                                  Runnable cancelHook) {
        String existing = currentId;
        if (existing != null && active.containsKey(existing)) {
            return buildExecutionInProgress(existing);
        }

        long startMillis = System.currentTimeMillis();
        String stdoutBefore = logSnapshot.get();
        int resultsRowsBefore = resultsRowCount.getAsInt();
        String execId = "exec-" + counter.incrementAndGet();
        // Worker stashes its captured stderr here in the finally block; the
        // caller thread reads it when building the completed envelope. We can't
        // safely peek at the worker's ThreadLocal from another thread, so the
        // running envelope reports stderr="" until the worker actually finishes.
        AtomicReference<String> capturedStderr = new AtomicReference<>("");

        Callable<Object> wrapped = () -> {
            SourceTracker.setMcpActive(true);
            if (stderrTee != null) stderrTee.beginCapture();
            try {
                return body.call();
            } finally {
                SourceTracker.setMcpActive(false);
                if (stderrTee != null) capturedStderr.set(stderrTee.endCapture());
            }
        };

        // Acquire lock so the user sees the "busy" signal as soon as possible.
        // Failure is logged but never aborts the execution.
        try {
            lockAcquire.run();
        } catch (Throwable t) {
            System.err.println("[fiji-mcp] lock acquire failed: " + t);
        }

        DialogWatchdog watchdog = watchdogFactory != null ? watchdogFactory.get() : null;
        Future<Object> future = worker.submit(wrapped);
        Slot slot = new Slot(execId, type, future, startMillis, stdoutBefore,
                             hardTimeoutSeconds, cancelHook, capturedStderr, watchdog,
                             resultsRowsBefore);
        slot.hardCancel = scheduler.schedule(
                () -> internalCancel(slot, CancelReason.HARD_TIMEOUT),
                hardTimeoutSeconds, TimeUnit.SECONDS);
        active.put(execId, slot);
        currentId = execId;
        if (watchdog != null) watchdog.start();

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
            Throwable promoted = maybePromoteToDialogDismissed(slot, null);
            return buildCompleted(slot, value, promoted);
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
        if (slot.cancelHook != null) {
            try {
                slot.cancelHook.run();
            } catch (Throwable t) {
                // A misbehaving hook must not corrupt reporter state or leak the slot.
                System.err.println("[fiji-mcp] cancel hook threw: " + t);
            }
        }
    }

    private void reap(Slot slot) {
        if (slot.hardCancel != null) {
            slot.hardCancel.cancel(false);
        }
        if (slot.watchdog != null) {
            slot.watchdog.stop();
        }
        try {
            lockRelease.run();
        } catch (Throwable t) {
            System.err.println("[fiji-mcp] lock release failed: " + t);
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
        env.addProperty("stderr", slot.capturedStderr.get());
        env.add("value", value == null ? JsonNull.INSTANCE
                                       : new JsonPrimitive(String.valueOf(value)));
        env.add("error", error == null ? JsonNull.INSTANCE
                                       : buildError(error, slot.type));
        env.addProperty("duration_ms", System.currentTimeMillis() - slot.startMillis);
        env.add("execution_id", JsonNull.INSTANCE);
        env.addProperty("active_image", activeImageTitle.get());
        addDismissedDialogs(env, slot);
        addResultsSnapshot(env, slot);
        return env;
    }

    private JsonObject buildRunning(Slot slot) {
        JsonObject env = new JsonObject();
        env.addProperty("status", "running");
        env.addProperty("stdout", diff(slot.stdoutBefore, logSnapshot.get()));
        // Worker is still alive — its stderr ThreadLocal is not safe to read
        // from this thread. The completed envelope (or wait_for_execution) will
        // surface it once the worker's finally block has run.
        env.addProperty("stderr", "");
        env.add("value", JsonNull.INSTANCE);
        env.add("error", JsonNull.INSTANCE);
        env.addProperty("duration_ms", System.currentTimeMillis() - slot.startMillis);
        env.addProperty("execution_id", slot.id);
        env.addProperty("active_image", activeImageTitle.get());
        addDismissedDialogs(env, slot);
        return env;
    }

    private JsonObject buildExecutionInProgress(String busyId) {
        JsonObject env = new JsonObject();
        env.addProperty("status", "completed");
        env.addProperty("stdout", "");
        env.addProperty("stderr", "");
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
        addEmptyDismissedDialogs(env);
        return env;
    }

    private JsonObject buildUnknownExecution(String requestedId) {
        JsonObject env = new JsonObject();
        env.addProperty("status", "completed");
        env.addProperty("stdout", "");
        env.addProperty("stderr", "");
        env.add("value", JsonNull.INSTANCE);
        JsonObject err = new JsonObject();
        err.addProperty("message", "No execution with id " + requestedId);
        err.addProperty("type", "UnknownExecution");
        err.add("line", JsonNull.INSTANCE);
        env.add("error", err);
        env.addProperty("duration_ms", 0);
        env.add("execution_id", JsonNull.INSTANCE);
        env.addProperty("active_image", activeImageTitle.get());
        addEmptyDismissedDialogs(env);
        return env;
    }

    private Throwable maybePromoteToDialogDismissed(Slot slot, Throwable existing) {
        if (existing != null) return existing;
        if (slot.watchdog == null) return null;
        List<DismissedDialog> dismissed = slot.watchdog.dismissed();
        if (dismissed.isEmpty()) return null;
        return new DialogDismissedException(dismissed);
    }

    private void addDismissedDialogs(JsonObject env, Slot slot) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        if (slot != null && slot.watchdog != null) {
            for (DismissedDialog d : slot.watchdog.dismissed()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("title", d.title());
                entry.addProperty("text", d.text());
                entry.addProperty("when_ms", d.whenMs());
                arr.add(entry);
            }
        }
        env.add("dismissed_dialogs", arr);
    }

    private void addEmptyDismissedDialogs(JsonObject env) {
        env.add("dismissed_dialogs", new com.google.gson.JsonArray());
    }

    private void addResultsSnapshot(JsonObject env, Slot slot) {
        int rowsNow = resultsRowCount.getAsInt();
        if (rowsNow == slot.resultsRowsBefore) {
            env.add("results_snapshot", JsonNull.INSTANCE);
            return;
        }
        JsonObject snapshot = resultsSnapshot.get();
        env.add("results_snapshot", snapshot != null ? snapshot : JsonNull.INSTANCE);
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
            if (cur instanceof javax.script.ScriptException
                    && ((javax.script.ScriptException) cur).getLineNumber() > 0) {
                err.addProperty("line", ((javax.script.ScriptException) cur).getLineNumber());
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
        if (t instanceof DialogDismissedException) return "DialogDismissed";
        switch (typeHint) {
            case "macro":   return "MacroError";
            case "script":  return "ScriptError";
            case "command": return "CommandError";
            default:        return "ExecutionError";
        }
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
        final int resultsRowsBefore;
        final int hardTimeoutSeconds;
        final Runnable cancelHook;
        final AtomicReference<String> capturedStderr;
        final DialogWatchdog watchdog;
        volatile ScheduledFuture<?> hardCancel;
        volatile CancelReason cancelReason;

        Slot(String id, String type, Future<Object> future,
             long startMillis, String stdoutBefore, int hardTimeoutSeconds,
             Runnable cancelHook, AtomicReference<String> capturedStderr,
             DialogWatchdog watchdog, int resultsRowsBefore) {
            this.id = id;
            this.type = type;
            this.future = future;
            this.startMillis = startMillis;
            this.stdoutBefore = stdoutBefore;
            this.resultsRowsBefore = resultsRowsBefore;
            this.hardTimeoutSeconds = hardTimeoutSeconds;
            this.cancelHook = cancelHook;
            this.capturedStderr = capturedStderr;
            this.watchdog = watchdog;
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
