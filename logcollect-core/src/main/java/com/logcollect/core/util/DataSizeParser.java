package com.logcollect.core.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据大小解析与格式化工具。
 *
 * <p>支持形如 {@code 512KB}、{@code 1MB}、{@code 1.5GB} 的单位表达，
 * 同时也支持直接传入纯数字字节值。
 */
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

    /**
     * 解析大小字符串为字节数。
     *
     * @param sizeStr 大小字符串，例如 {@code 1MB}、{@code 512KB}、{@code 1024}
     * @return 对应字节数
     * @throws IllegalArgumentException 当格式非法或为空时抛出
     */
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

    /**
     * 将字节数格式化为可读字符串。
     *
     * @param bytes 字节数
     * @return 人类可读格式（B/KB/MB/GB）
     */
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
