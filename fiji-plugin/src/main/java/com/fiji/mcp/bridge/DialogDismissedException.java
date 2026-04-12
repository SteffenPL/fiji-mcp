package com.fiji.mcp.bridge;

import java.util.List;

/**
 * Synthesized by {@link ExecutionReporter} when {@link DialogWatchdog}
 * dismissed at least one dialog during an execution and the worker body
 * produced no other error. Maps to envelope {@code error.type ==
 * "DialogDismissed"}.
 */
public class DialogDismissedException extends RuntimeException {

    public DialogDismissedException(List<DismissedDialog> dismissed) {
        super(buildMessage(dismissed));
    }

    private static String buildMessage(List<DismissedDialog> dismissed) {
        StringBuilder sb = new StringBuilder("Bridge dismissed ");
        sb.append(dismissed.size());
        sb.append(" modal dialog");
        if (dismissed.size() != 1) sb.append("s");
        sb.append(": ");
        for (int i = 0; i < dismissed.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("'").append(dismissed.get(i).title()).append("'");
        }
        return sb.toString();
    }
}
