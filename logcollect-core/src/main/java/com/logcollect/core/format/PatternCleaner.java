package com.logcollect.core.format;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 日志 pattern 清理器：去除颜色、PID 等收集场景不需要的占位符。
 */
public final class PatternCleaner {

    /**
     * 控制台专用包装 converter。
     *
     * <p>这些 converter 的公共特征是：包裹一段内部 pattern（通常用于颜色/样式/对齐），
     * 对日志采集文本本身不提供业务字段语义。
     */
    private static final Set<String> CONSOLE_WRAPPER_CONVERTERS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "clr", "highlight", "style", "pad", "replace",
                    "black", "red", "green", "yellow", "blue", "magenta", "cyan",
                    "white", "gray", "grey",
                    "bold", "underline", "faint", "blink", "reverse",
                    "boldred", "boldgreen", "boldyellow", "boldblue",
                    "boldmagenta", "boldcyan", "boldwhite",
                    "bgblack", "bgred", "bggreen", "bgyellow",
                    "bgblue", "bgmagenta", "bgcyan", "bgwhite"
            )));

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

        // 2. 去除控制台专用包装 converter（颜色/样式/对齐/replace）
        result = stripConsoleWrappers(result);

        // 3. 去除 PID
        result = PID_PATTERN.matcher(result).replaceAll("");

        // 4. 去除装饰性分隔符 "---"
        result = SEPARATOR_PATTERN.matcher(result).replaceAll(" ");

        // 5. 压缩连续空格
        result = MULTI_SPACE.matcher(result).replaceAll(" ");

        return result.trim();
    }

    private static String stripConsoleWrappers(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return pattern;
        }
        String current = pattern;
        while (true) {
            String next = stripConsoleWrappersOnce(current);
            if (current.equals(next)) {
                return next;
            }
            current = next;
        }
    }

    private static String stripConsoleWrappersOnce(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length());
        int i = 0;
        int len = pattern.length();
        while (i < len) {
            char ch = pattern.charAt(i);
            if (ch != '%' || i + 1 >= len) {
                out.append(ch);
                i++;
                continue;
            }
            if (pattern.charAt(i + 1) == '%') {
                out.append("%%");
                i += 2;
                continue;
            }

            int nameStart = i + 1;
            int nameEnd = nameStart;
            while (nameEnd < len && Character.isLetter(pattern.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd == nameStart) {
                out.append(ch);
                i++;
                continue;
            }

            String converter = pattern.substring(nameStart, nameEnd);
            if (!CONSOLE_WRAPPER_CONVERTERS.contains(converter.toLowerCase(Locale.ROOT))) {
                out.append(pattern, i, nameEnd);
                i = nameEnd;
                continue;
            }

            int argumentStart = skipWhitespaces(pattern, nameEnd);
            if (argumentStart >= len) {
                i = nameEnd;
                continue;
            }
            char open = pattern.charAt(argumentStart);
            if (open != '(' && open != '{') {
                i = nameEnd;
                continue;
            }

            char close = open == '(' ? ')' : '}';
            int argumentEnd = findMatching(pattern, argumentStart, open, close);
            if (argumentEnd < 0) {
                out.append(pattern.charAt(i));
                i++;
                continue;
            }

            out.append(pattern, argumentStart + 1, argumentEnd);
            i = argumentEnd + 1;

            // %style{...}{bold} / %replace{...}{regex}{rep} / %pad(...){N}
            while (true) {
                int cursor = i;
                int next = skipWhitespaces(pattern, i);
                if (next >= len || pattern.charAt(next) != '{') {
                    i = cursor;
                    break;
                }
                int optionEnd = findMatching(pattern, next, '{', '}');
                if (optionEnd < 0) {
                    i = next + 1;
                    break;
                }
                i = optionEnd + 1;
            }
        }
        return out.toString();
    }

    private static int skipWhitespaces(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int findMatching(String text, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
