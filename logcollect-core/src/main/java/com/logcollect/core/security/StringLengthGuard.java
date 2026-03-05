package com.logcollect.core.security;

/**
 * @deprecated 自 v1.2.0 起框架内部不再调用该类。
 * 日志内容不再截断，超大日志由 Direct Flush + 降级通道处理。
 * 该类仅保留给直接依赖它的用户代码做兼容。
 */
@Deprecated
public final class StringLengthGuard {

    private static final int DEFAULT_MAX_CONTENT_LENGTH = 32 * 1024;
    private static final int DEFAULT_MAX_THROWABLE_LENGTH = 64 * 1024;
    private static final String TRUNCATION_MARKER = "\n... [truncated by LogCollect]";
    private static final StringLengthGuard DEFAULT_GUARD =
            new StringLengthGuard(DEFAULT_MAX_CONTENT_LENGTH, DEFAULT_MAX_THROWABLE_LENGTH);

    private final int maxContentLength;
    private final int maxThrowableLength;

    public StringLengthGuard(int maxContentLength, int maxThrowableLength) {
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be > 0");
        }
        if (maxThrowableLength <= 0) {
            throw new IllegalArgumentException("maxThrowableLength must be > 0");
        }
        this.maxContentLength = maxContentLength;
        this.maxThrowableLength = maxThrowableLength;
    }

    public static StringLengthGuard withDefaults() {
        return DEFAULT_GUARD;
    }

    public static String guardContent(String raw, int maxLength) {
        return truncate(raw, maxLength <= 0 ? DEFAULT_MAX_CONTENT_LENGTH : maxLength);
    }

    public static String guardThrowable(String raw, int maxLength) {
        return truncate(raw, maxLength <= 0 ? DEFAULT_MAX_THROWABLE_LENGTH : maxLength);
    }

    public String guardContent(String raw) {
        return truncate(raw, maxContentLength);
    }

    public String guardThrowable(String raw) {
        return truncate(raw, maxThrowableLength);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + TRUNCATION_MARKER;
    }
}
