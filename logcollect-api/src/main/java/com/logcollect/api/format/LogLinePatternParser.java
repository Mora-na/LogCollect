package com.logcollect.api.format;

import com.logcollect.api.model.LogEntry;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级日志行 Pattern 解析器。
 *
 * <p>解析类似 Logback/Log4j2 的格式占位符，仅支持 LogEntry 字段可覆盖的子集。
 * 零外部依赖，纯 JDK 实现。
 */
public final class LogLinePatternParser {

    /**
     * 匹配格式占位符：%[-][minWidth][.maxWidth]conversion[{argument}]
     */
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "%(-)?(\\d+)?(?:\\.(\\d+))?"
                    + "(d|p|level|t|thread|c|logger|C|loggerFull|m|msg|ex|throwable|n|wEx)"
                    + "(?:\\{([^}]*)\\})?"
    );

    /** pattern -> 解析结果缓存 */
    private static final ConcurrentMap<String, List<Segment>> PARSE_CACHE =
            new ConcurrentHashMap<String, List<Segment>>(8);

    /** DateTimeFormatter 缓存 */
    private static final ConcurrentMap<String, DateTimeFormatter> DTF_CACHE =
            new ConcurrentHashMap<String, DateTimeFormatter>(4);

    private LogLinePatternParser() {
    }

    /**
     * 按指定 pattern 格式化 LogEntry。
     */
    public static String format(LogEntry entry, String pattern) {
        if (entry == null) {
            return "";
        }
        String safePattern = (pattern == null || pattern.isEmpty())
                ? LogLineDefaults.getDetectedPattern()
                : pattern;

        List<Segment> segments = PARSE_CACHE.computeIfAbsent(safePattern, LogLinePatternParser::parse);

        StringBuilder sb = new StringBuilder(256);
        for (Segment seg : segments) {
            seg.render(entry, sb);
        }
        return sb.toString();
    }

    private static List<Segment> parse(String pattern) {
        List<Segment> segments = new ArrayList<Segment>();
        Matcher matcher = TOKEN_PATTERN.matcher(pattern);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                segments.add(new LiteralSegment(pattern.substring(lastEnd, matcher.start())));
            }

            boolean leftAlign = "-".equals(matcher.group(1));
            int minWidth = parseIntOrZero(matcher.group(2));
            int maxWidth = parseIntOrZero(matcher.group(3));
            String conversion = matcher.group(4);
            String argument = matcher.group(5);

            segments.add(new PlaceholderSegment(conversion, argument, leftAlign, minWidth, maxWidth));
            lastEnd = matcher.end();
        }

        if (lastEnd < pattern.length()) {
            segments.add(new LiteralSegment(pattern.substring(lastEnd)));
        }

        return segments;
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    private interface Segment {
        void render(LogEntry entry, StringBuilder sb);
    }

    private static final class LiteralSegment implements Segment {
        private final String text;

        private LiteralSegment(String text) {
            this.text = text;
        }

        @Override
        public void render(LogEntry entry, StringBuilder sb) {
            sb.append(text);
        }
    }

    private static final class PlaceholderSegment implements Segment {
        private final String conversion;
        private final String argument;
        private final boolean leftAlign;
        private final int minWidth;
        private final int maxWidth;

        private PlaceholderSegment(String conversion,
                                   String argument,
                                   boolean leftAlign,
                                   int minWidth,
                                   int maxWidth) {
            this.conversion = conversion;
            this.argument = argument;
            this.leftAlign = leftAlign;
            this.minWidth = minWidth;
            this.maxWidth = maxWidth;
        }

        @Override
        public void render(LogEntry entry, StringBuilder sb) {
            String value = resolveValue(entry);
            if (value == null) {
                value = "";
            }
            applyWidth(sb, value);
        }

        private void applyWidth(StringBuilder sb, String value) {
            // 最大宽度截断（从左侧截断，保留右侧）
            if (maxWidth > 0 && value.length() > maxWidth) {
                value = value.substring(value.length() - maxWidth);
            }

            // 最小宽度填充
            if (minWidth > 0 && value.length() < minWidth) {
                int padding = minWidth - value.length();
                if (leftAlign) {
                    sb.append(value);
                    for (int i = 0; i < padding; i++) {
                        sb.append(' ');
                    }
                } else {
                    for (int i = 0; i < padding; i++) {
                        sb.append(' ');
                    }
                    sb.append(value);
                }
            } else {
                sb.append(value);
            }
        }

        private String resolveValue(LogEntry entry) {
            switch (conversion) {
                case "d":
                    return formatTime(entry);
                case "p":
                case "level":
                    return entry.getLevel();
                case "t":
                case "thread":
                    return entry.getThreadName();
                case "c":
                case "logger":
                    return abbreviateLoggerName(entry.getLoggerName(), parseIntOrZero(argument));
                case "C":
                case "loggerFull":
                    return entry.getLoggerName();
                case "m":
                case "msg":
                    return entry.getContent();
                case "ex":
                case "throwable":
                case "wEx":
                    return resolveThrowable(entry);
                case "n":
                    return System.lineSeparator();
                default:
                    return "%" + conversion;
            }
        }

        private String formatTime(LogEntry entry) {
            if (entry.getTime() == null) {
                return "";
            }
            String datePattern = (argument != null && !argument.isEmpty())
                    ? argument
                    : "yyyy-MM-dd HH:mm:ss.SSS";
            try {
                DateTimeFormatter formatter = DTF_CACHE.computeIfAbsent(
                        datePattern,
                        DateTimeFormatter::ofPattern);
                return entry.getTime().format(formatter);
            } catch (Exception e) {
                return entry.getTime().toString();
            }
        }

        private String resolveThrowable(LogEntry entry) {
            if (!entry.hasThrowable()) {
                return "";
            }
            return "\n" + entry.getThrowableString();
        }
    }

    /**
     * 缩写 Logger 全限定名。
     *
     * <p>当全名长度超过 targetLength 时，从左侧开始逐段缩写为首字母，
     * 保持最右侧的类名始终完整。
     */
    static String abbreviateLoggerName(String loggerName, int targetLength) {
        if (loggerName == null) {
            return "";
        }
        if (targetLength <= 0 || loggerName.length() <= targetLength) {
            return loggerName;
        }

        String[] segments = loggerName.split("\\.");
        if (segments.length <= 1) {
            return loggerName;
        }

        boolean[] abbreviated = new boolean[segments.length];
        int currentLength = loggerName.length();

        for (int i = 0; i < segments.length - 1; i++) {
            if (currentLength <= targetLength) {
                break;
            }
            int saved = segments[i].length() - 1;
            abbreviated[i] = true;
            currentLength -= saved;
        }

        StringBuilder sb = new StringBuilder(currentLength);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(abbreviated[i] ? segments[i].charAt(0) : segments[i]);
        }
        return sb.toString();
    }
}
