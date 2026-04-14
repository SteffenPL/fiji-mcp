package com.fiji.mcp.bridge;

/**
 * One entry in {@code envelope.dismissed_dialogs[]}. Captured by
 * {@link DialogWatchdog} at the moment of dispose, then serialized
 * by {@link ExecutionReporter} into the envelope.
 */
public final class DismissedDialog {

    private final String title;
    private final String text;
    private final long whenMs;

    public DismissedDialog(String title, String text, long whenMs) {
        this.title = title;
        this.text = text;
        this.whenMs = whenMs;
    }

    public String title() { return title; }
    public String text() { return text; }
    public long whenMs() { return whenMs; }
}
