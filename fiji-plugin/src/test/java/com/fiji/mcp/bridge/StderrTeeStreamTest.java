package com.fiji.mcp.bridge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class StderrTeeStreamTest {

    @Test
    void writesPassThroughToDelegateWhenNoCaptureActive() {
        ByteArrayOutputStream delegateBuf = new ByteArrayOutputStream();
        StderrTeeStream tee =
                new StderrTeeStream(new PrintStream(delegateBuf, true));

        tee.println("hello");

        assertEquals("hello" + System.lineSeparator(), delegateBuf.toString());
    }

    @Test
    void capturesWritesBetweenBeginAndEnd() {
        ByteArrayOutputStream delegateBuf = new ByteArrayOutputStream();
        StderrTeeStream tee =
                new StderrTeeStream(new PrintStream(delegateBuf, true));

        tee.beginCapture();
        tee.println("captured");
        String captured = tee.endCapture();

        // Tee'd: still goes to the underlying stream, AND captured.
        assertEquals("captured" + System.lineSeparator(), delegateBuf.toString());
        assertEquals("captured" + System.lineSeparator(), captured);
    }

    @Test
    void capturesAcrossThreadsDuringWindow() throws Exception {
        // Global semantics: any thread writing to the tee while a window is
        // open contributes to the capture. This is required because SciJava
        // ScriptService runs scripts on its own thread pool — the worker
        // body is just blocked in future.get() while the actual stderr is
        // produced on a different thread.
        StderrTeeStream tee = new StderrTeeStream(
                new PrintStream(new ByteArrayOutputStream(), true));

        tee.beginCapture();
        Thread sub = new Thread(() -> tee.println("from-sub-thread"));
        sub.start();
        sub.join();
        String captured = tee.endCapture();

        assertEquals("from-sub-thread" + System.lineSeparator(), captured);
    }

    @Test
    void writesAfterEndCaptureArePassThroughOnly() {
        ByteArrayOutputStream delegateBuf = new ByteArrayOutputStream();
        StderrTeeStream tee = new StderrTeeStream(new PrintStream(delegateBuf, true));

        tee.beginCapture();
        tee.println("during");
        String captured = tee.endCapture();
        tee.println("after");

        assertEquals("during" + System.lineSeparator(), captured);
        assertTrue(delegateBuf.toString().contains("during"));
        assertTrue(delegateBuf.toString().contains("after"));
    }

    @Test
    void consecutiveCapturesAreIndependent() {
        StderrTeeStream tee = new StderrTeeStream(
                new PrintStream(new ByteArrayOutputStream(), true));

        tee.beginCapture();
        tee.println("first");
        String first = tee.endCapture();

        tee.beginCapture();
        tee.println("second");
        String second = tee.endCapture();

        assertEquals("first" + System.lineSeparator(), first);
        assertEquals("second" + System.lineSeparator(), second);
    }

    @Test
    void capturesPrintStackTraceOutput() {
        StderrTeeStream tee = new StderrTeeStream(
                new PrintStream(new ByteArrayOutputStream(), true));

        tee.beginCapture();
        new RuntimeException("boom-fm-50jy").printStackTrace(tee);
        String captured = tee.endCapture();

        // The whole point of fm-50jy: IJ.handleException calls printStackTrace,
        // which routes through write(byte[], int, int) — must be captured.
        assertTrue(captured.contains("boom-fm-50jy"),
                "expected captured stack trace, got: " + captured);
        assertTrue(captured.contains("StderrTeeStreamTest"),
                "expected stack frame in captured output");
    }

    @Test
    void endCaptureWithoutBeginReturnsEmptyString() {
        StderrTeeStream tee = new StderrTeeStream(
                new PrintStream(new ByteArrayOutputStream(), true));
        assertEquals("", tee.endCapture());
    }
}
