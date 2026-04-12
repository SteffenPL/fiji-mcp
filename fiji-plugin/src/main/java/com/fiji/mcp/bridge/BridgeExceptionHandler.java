package com.fiji.mcp.bridge;

/**
 * Routes ImageJ exception traces through {@code System.err} so the
 * {@link StderrTeeStream} can capture them into envelope.stderr.
 *
 * <p>The default {@code ij.IJ.handleException} implementation in GUI mode
 * opens a "Exception" {@link ij.text.TextWindow} popup and writes nothing
 * to any stream — making the stack trace invisible to the bridge envelope
 * (fp-89zk). Installing this handler via
 * {@code ij.IJ.setExceptionHandler(new BridgeExceptionHandler())} replaces
 * that popup-only path with a stream-only path that the existing tee
 * already understands.
 *
 * <p>The popup is intentionally dropped: when the bridge is running, the
 * agent surfaces the trace through envelope.stderr and a Fiji popup would
 * just accumulate noise during automated workflows.
 */
public class BridgeExceptionHandler implements ij.IJ.ExceptionHandler {

    @Override
    public void handle(Throwable t) {
        // printStackTrace(PrintStream) walks the cause chain natively, so
        // we don't need to format it manually.
        t.printStackTrace(System.err);
    }
}
