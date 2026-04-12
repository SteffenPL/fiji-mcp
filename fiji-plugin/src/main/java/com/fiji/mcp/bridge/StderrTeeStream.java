package com.fiji.mcp.bridge;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * A {@link PrintStream} wrapper that tees writes into a single, process-wide
 * capture buffer when capture is active, and otherwise passes through to the
 * delegate untouched. Used to capture stderr produced during a single
 * fiji-mcp execution window — including stderr emitted by threads other
 * than the worker (e.g. SciJava ScriptService thread pool, IJ command
 * dispatch threads).
 *
 * <p>Install once at startup:
 * <pre>{@code
 * System.setErr(new StderrTeeStream(System.err));
 * }</pre>
 * Then bracket the worker code with {@link #beginCapture()} /
 * {@link #endCapture()}.
 *
 * <p><strong>Concurrency model:</strong> capture is process-global, not
 * thread-local. ExecutionReporter serializes executions via its single-slot
 * worker, so at most one capture window is active at any time. Any thread
 * writing to stderr while a window is open contributes to the capture; any
 * thread writing while no window is open passes through to the original
 * stderr unchanged. This is intentional: SciJava's ScriptService runs script
 * bodies on its own thread pool, so a thread-keyed design would silently
 * miss stderr produced from script execution — which is the original bug
 * (fm-50jy).
 *
 * <p>Only the two primitive {@code write} overloads are intercepted. Every
 * higher-level PrintStream method (println, printf, print, write(byte[]),
 * Throwable.printStackTrace target) eventually funnels into one of these.
 */
public class StderrTeeStream extends PrintStream {

    private final Object captureLock = new Object();
    private volatile ByteArrayOutputStream activeBuffer;

    public StderrTeeStream(PrintStream delegate) {
        super(delegate, true);
    }

    /** Open a capture window. Subsequent writes from any thread are buffered. */
    public void beginCapture() {
        synchronized (captureLock) {
            activeBuffer = new ByteArrayOutputStream();
        }
    }

    /**
     * Close the capture window and return everything written between
     * {@link #beginCapture()} and now. Returns "" if no window was open.
     */
    public String endCapture() {
        synchronized (captureLock) {
            ByteArrayOutputStream buf = activeBuffer;
            activeBuffer = null;
            return buf == null ? "" : buf.toString();
        }
    }

    @Override
    public void write(int b) {
        ByteArrayOutputStream buf = activeBuffer;
        if (buf != null) {
            synchronized (captureLock) {
                // Re-check after acquiring the lock — the window may have been
                // closed between the volatile read and the synchronized block.
                if (activeBuffer != null) {
                    activeBuffer.write(b);
                }
            }
        }
        super.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        ByteArrayOutputStream buf = activeBuffer;
        if (buf != null) {
            synchronized (captureLock) {
                if (activeBuffer != null) {
                    activeBuffer.write(b, off, len);
                }
            }
        }
        super.write(b, off, len);
    }
}
