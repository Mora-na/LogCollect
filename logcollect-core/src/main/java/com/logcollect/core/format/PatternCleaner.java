package com.logcollect.core.format;

import java.util.regex.Pattern;

/**
 * 日志 pattern 清理器：去除颜色、PID 等收集场景不需要的占位符。
 */
public final class PatternCleaner {

    // Logback Spring Extension：%clr(content){color}
    private static final Pattern CLR_PATTERN =
            Pattern.compile("%clr\\(([^)]+)\\)(\\{[^}]*\\})?");

    // ANSI 高亮：%highlight(content)、%style(content){color}
    private static final Pattern HIGHLIGHT_PATTERN =
            Pattern.compile("%(highlight|style)\\(([^)]+)\\)(\\{[^}]*\\})?");

    // PID 占位符：${PID:- } 或 %pid
    private static final Pattern PID_PATTERN =
            Pattern.compile("(\\$\\{PID:-?\\s?\\}|%pid)");

    // Spring 属性引用：${LOG_DATEFORMAT_PATTERN:-default}
    private static final Pattern SPRING_PROP_PATTERN =
            Pattern.compile("\\$\\{[a-zA-Z0-9._\\-]+:-([^}]+)\\}");

    // 连续空格压缩
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    // 分隔符 "---"（Spring Boot 默认控制台中的装饰性分隔符）
    private static final Pattern SEPARATOR_PATTERN =
            Pattern.compile("\\s*---\\s*");

    private PatternCleaner() {
    }

    /**
     * 清理原始 pattern，去除颜色/PID/装饰性元素，解析 Spring 属性默认值。
     *
     * @param rawPattern 原始 pattern
     * @return 清理后的 pattern，适合文本存储场景
     */
    public static String clean(String rawPattern) {
        if (rawPattern == null || rawPattern.isEmpty()) {
            return null;
        }

        String result = rawPattern;

        // 1. 解析 Spring 属性引用，提取默认值
        result = SPRING_PROP_PATTERN.matcher(result).replaceAll("$1");

        // 2. 去除 %clr() 包装，保留内容
        result = CLR_PATTERN.matcher(result).replaceAll("$1");

        // 3. 去除 %highlight/%style 包装
        result = HIGHLIGHT_PATTERN.matcher(result).replaceAll("$2");

        // 4. 去除 PID
        result = PID_PATTERN.matcher(result).replaceAll("");

        // 5. 去除装饰性分隔符 "---"
        result = SEPARATOR_PATTERN.matcher(result).replaceAll(" ");

        // 6. 压缩连续空格
        result = MULTI_SPACE.matcher(result).replaceAll(" ");

        return result.trim();
    }
}
