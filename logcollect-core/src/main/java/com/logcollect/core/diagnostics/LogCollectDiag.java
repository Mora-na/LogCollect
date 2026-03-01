package com.logcollect.core.diagnostics;

public final class LogCollectDiag {

    private static volatile boolean enabled =
            "true".equalsIgnoreCase(System.getProperty("logcollect.debug"));

    private LogCollectDiag() {
    }

    public static void debug(String format, Object... args) {
        if (enabled) {
            System.err.printf("[LogCollect-DEBUG] " + format + "%n", args);
        }
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }
}
