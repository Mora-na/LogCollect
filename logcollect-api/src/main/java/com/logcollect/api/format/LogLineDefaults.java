package com.logcollect.api.format;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日志行格式默认值持有者。
 */
public final class LogLineDefaults {

    private static final String FALLBACK_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%t] %c{1} - %m%ex%n";

    private static volatile String detectedPattern;
    private static final AtomicInteger VERSION = new AtomicInteger(0);

    private LogLineDefaults() {
    }

    public static void setDetectedPattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return;
        }
        detectedPattern = pattern.trim();
        VERSION.incrementAndGet();
        LogLinePatternParser.invalidateCache();
    }

    public static String getEffectivePattern() {
        String pattern = detectedPattern;
        return pattern == null ? FALLBACK_PATTERN : pattern;
    }

    public static String getFallbackPattern() {
        return FALLBACK_PATTERN;
    }

    public static int getVersion() {
        return VERSION.get();
    }
}
