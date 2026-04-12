package com.fiji.mcp.bridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Per-execution polling watchdog that detects new modal dialogs appearing
 * during a fiji-mcp execution and disposes them. Snapshot semantics protect
 * dialogs that existed before {@link #start()} from ever being dismissed.
 *
 * <p>Pure logic — no AWT dependency. Production wires
 * {@link AwtDialogProbe#currentAll()} as the probe source; tests pass fakes.
 *
 * <p>Lifecycle is per-execution: construct, start, stop, read {@link #dismissed()}.
 * Reuse across executions is not supported and not needed.
 */
public class DialogWatchdog {

    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 5;

    private final Supplier<List<DialogProbe>> probeSource;
    private final ScheduledExecutorService scheduler;
    private final long pollIntervalMs;
    private final int maxDismissedCap;

    private final Set<Object> snapshotKeys = new HashSet<>();
    private final List<DismissedDialog> dismissed = new ArrayList<>();
    private volatile ScheduledFuture<?> task;
    private volatile long startMillis;
    private int consecutivePollFailures;

    public DialogWatchdog(Supplier<List<DialogProbe>> probeSource,
                          ScheduledExecutorService scheduler,
                          long pollIntervalMs,
                          int maxDismissedCap) {
        this.probeSource = probeSource;
        this.scheduler = scheduler;
        this.pollIntervalMs = pollIntervalMs;
        this.maxDismissedCap = maxDismissedCap;
    }

    /** Snapshot pre-existing dialog keys and start the poll loop. */
    public synchronized void start() {
        snapshotKeys.clear();
        consecutivePollFailures = 0;
        try {
            for (DialogProbe p : probeSource.get()) {
                snapshotKeys.add(p.key());
            }
        } catch (Throwable t) {
            System.err.println("[fiji-mcp] watchdog snapshot threw: " + t);
            // Empty snapshot — defensive. The poll loop will handle further
            // failures with its own counter.
        }
        startMillis = System.currentTimeMillis();
        task = scheduler.scheduleAtFixedRate(
                this::poll, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** Cancel the scheduled poll. Idempotent. */
    public void stop() {
        ScheduledFuture<?> t = task;
        if (t != null) {
            t.cancel(false);
            task = null;
        }
    }

    /** Dialogs disposed since {@link #start()}. Never null; empty if none. */
    public synchronized List<DismissedDialog> dismissed() {
        return new ArrayList<>(dismissed);
    }

    private synchronized void poll() {
        try {
            List<DialogProbe> current = probeSource.get();
            for (DialogProbe probe : current) {
                if (dismissed.size() >= maxDismissedCap) {
                    return;
                }
                if (probe.isModalAndVisible() && !snapshotKeys.contains(probe.key())) {
                    String title = probe.title();
                    String text = probe.text();
                    try {
                        probe.dispose();
                        dismissed.add(new DismissedDialog(
                                title, text, System.currentTimeMillis() - startMillis));
                    } catch (Throwable t) {
                        System.err.println("[fiji-mcp] watchdog dispose failed: " + t);
                        // Do not record — caller did not get confirmation.
                    }
                }
            }
            consecutivePollFailures = 0;
        } catch (Throwable t) {
            consecutivePollFailures++;
            System.err.println("[fiji-mcp] watchdog poll threw: " + t);
            if (consecutivePollFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                System.err.println("[fiji-mcp] watchdog disabled for this execution");
                stop();
            }
        }
    }
}
