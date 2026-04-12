package com.fiji.mcp.bridge;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Label;
import java.awt.TextComponent;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Production probe wrapping a {@link java.awt.Dialog}. Walks the dialog's
 * AWT/Swing component tree for {@link #text()}, best-effort.
 *
 * <p>This is the only class in the watchdog subsystem that touches AWT.
 * It is not unit-tested — its correctness is verified by the live
 * verification step in the implementation plan.
 */
public final class AwtDialogProbe implements DialogProbe {

    private final Dialog dialog;

    public AwtDialogProbe(Dialog dialog) {
        this.dialog = dialog;
    }

    /** Snapshot all currently-open Dialog instances visible to AWT. */
    public static List<DialogProbe> currentAll() {
        Window[] windows = Window.getWindows();
        if (windows == null) return List.of();
        List<DialogProbe> result = new ArrayList<>(windows.length);
        for (Window w : windows) {
            if (w instanceof Dialog d) {
                result.add(new AwtDialogProbe(d));
            }
        }
        return result;
    }

    @Override
    public Object key() {
        return System.identityHashCode(dialog);
    }

    @Override
    public String title() {
        String t = dialog.getTitle();
        return t == null ? "" : t;
    }

    @Override
    public String text() {
        StringBuilder sb = new StringBuilder();
        collectText(dialog, sb);
        return sb.toString().trim();
    }

    @Override
    public boolean isModalAndVisible() {
        return dialog.isModal() && dialog.isVisible();
    }

    @Override
    public void dispose() {
        dialog.dispose();
    }

    /**
     * Recursively walks the container tree appending text from Label,
     * JLabel, and TextComponent children. Best-effort: silently skips
     * components whose text accessor throws.
     */
    private static void collectText(Container container, StringBuilder sb) {
        for (Component child : container.getComponents()) {
            try {
                if (child instanceof Label l) {
                    appendLine(sb, l.getText());
                } else if (child instanceof TextComponent tc) {
                    appendLine(sb, tc.getText());
                } else if (child instanceof javax.swing.JLabel jl) {
                    appendLine(sb, jl.getText());
                } else if (child instanceof javax.swing.text.JTextComponent jtc) {
                    appendLine(sb, jtc.getText());
                }
            } catch (Throwable ignored) {
                // Best-effort — skip components that throw on text access.
            }
            if (child instanceof Container c) {
                collectText(c, sb);
            }
        }
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line != null && !line.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
    }
}
