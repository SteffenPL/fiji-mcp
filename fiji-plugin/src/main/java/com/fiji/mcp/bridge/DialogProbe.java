package com.fiji.mcp.bridge;

/**
 * Pure abstraction over a modal dialog candidate, with no AWT dependency.
 * Production implementation is {@link AwtDialogProbe}; tests use a hand-rolled
 * recording fake.
 */
public interface DialogProbe {

    /** Stable identity for snapshot diffing. */
    Object key();

    /** Title for envelope reporting and synthesized error messages. */
    String title();

    /** Best-effort message text walked from the dialog's component tree. May be empty. */
    String text();

    /** True only if the dialog is currently shown AND modal. */
    boolean isModalAndVisible();

    /** Universal AWT/Swing close primitive. Unblocks any thread waiting on setVisible(true). */
    void dispose();
}
