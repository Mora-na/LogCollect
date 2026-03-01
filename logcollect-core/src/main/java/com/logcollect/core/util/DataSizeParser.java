package com.logcollect.core.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DataSizeParser {

    private static final Pattern SIZE_PATTERN =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB|TB)$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Long> UNITS;

    static {
        Map<String, Long> units = new HashMap<String, Long>();
        units.put("B", 1L);
        units.put("KB", 1024L);
        units.put("MB", 1024L * 1024);
        units.put("GB", 1024L * 1024 * 1024);
        units.put("TB", 1024L * 1024 * 1024 * 1024);
        UNITS = Collections.unmodifiableMap(units);
    }

    private DataSizeParser() {
    }

    public static long parseToBytes(String sizeStr) {
        if (sizeStr == null || sizeStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Size string cannot be null or empty");
        }
        String trimmed = sizeStr.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
        }

        Matcher matcher = SIZE_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Invalid size format: '" + sizeStr + "'. Expected: 1MB, 512KB, etc.");
        }
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toUpperCase();
        return (long) (value * UNITS.get(unit));
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
