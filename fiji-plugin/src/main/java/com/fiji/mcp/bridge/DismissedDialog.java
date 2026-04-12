package com.fiji.mcp.bridge;

/**
 * One entry in {@code envelope.dismissed_dialogs[]}. Captured by
 * {@link DialogWatchdog} at the moment of dispose, then serialized
 * by {@link ExecutionReporter} into the envelope.
 */
public record DismissedDialog(String title, String text, long whenMs) { }
