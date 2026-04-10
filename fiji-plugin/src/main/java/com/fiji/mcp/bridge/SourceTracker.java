package com.fiji.mcp.bridge;

/**
 * Thread-local flag to track whether current execution was initiated by MCP.
 * Used for source tagging in events ("user" vs "mcp").
 */
public class SourceTracker {
    private static final ThreadLocal<Boolean> MCP_ACTIVE =
            ThreadLocal.withInitial(() -> false);

    public static void setMcpActive(boolean active) {
        MCP_ACTIVE.set(active);
    }

    public static boolean isMcpActive() {
        return MCP_ACTIVE.get();
    }

    public static String getSource() {
        return MCP_ACTIVE.get() ? "mcp" : "user";
    }
}
