package com.logcollect.core.security;

public final class StringLengthGuard {

    private static final int DEFAULT_MAX_CONTENT_LENGTH = 32 * 1024;
    private static final int DEFAULT_MAX_THROWABLE_LENGTH = 64 * 1024;
    private static final String TRUNCATION_MARKER = "\n... [truncated by LogCollect]";

    private StringLengthGuard() {
    }

    public static String guardContent(String raw) {
        return truncate(raw, DEFAULT_MAX_CONTENT_LENGTH);
    }

    public static String guardContent(String raw, int maxLength) {
        return truncate(raw, maxLength <= 0 ? DEFAULT_MAX_CONTENT_LENGTH : maxLength);
    }

    public static String guardThrowable(String raw) {
        return truncate(raw, DEFAULT_MAX_THROWABLE_LENGTH);
    }

    public static String guardThrowable(String raw, int maxLength) {
        return truncate(raw, maxLength <= 0 ? DEFAULT_MAX_THROWABLE_LENGTH : maxLength);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + TRUNCATION_MARKER;
    }
}
