package com.fiji.mcp.bridge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class BridgeExceptionHandlerTest {

    @Test
    void handleWritesStackTraceToSystemErr() {
        // Regression for fp-89zk: ij.IJ.handleException in GUI mode opens
        // a TextWindow popup and writes nothing to any stream. Our handler
        // must route the trace through System.err so the StderrTeeStream
        // catches it during an execution window.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(buf, true));
        try {
            BridgeExceptionHandler handler = new BridgeExceptionHandler();
            handler.handle(new RuntimeException("synthetic for fp-89zk"));
        } finally {
            System.setErr(original);
        }

        String captured = buf.toString();
        assertTrue(captured.contains("synthetic for fp-89zk"),
                "expected message in trace, got: " + captured);
        assertTrue(captured.contains("BridgeExceptionHandlerTest"),
                "expected stack frame in trace, got: " + captured);
    }

    @Test
    void handleWritesNestedCauseChain() {
        // ImageJ wraps exceptions a lot — make sure the cause chain survives.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(buf, true));
        try {
            new BridgeExceptionHandler().handle(
                    new RuntimeException("outer",
                            new IllegalStateException("inner-fp-89zk")));
        } finally {
            System.setErr(original);
        }

        String captured = buf.toString();
        assertTrue(captured.contains("outer"));
        assertTrue(captured.contains("inner-fp-89zk"),
                "expected nested cause in trace, got: " + captured);
    }

    @Test
    void implementsIjExceptionHandlerInterface() {
        // Compile-time + runtime check that the handler can be installed
        // via ij.IJ.setExceptionHandler. This catches accidental refactors
        // that drop the interface.
        ij.IJ.ExceptionHandler handler = new BridgeExceptionHandler();
        assertNotNull(handler);
    }
}
