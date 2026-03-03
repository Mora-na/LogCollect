package com.logcollect.api.format;

import com.logcollect.api.model.LogEntry;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级日志行 Pattern 解析器。
 */
public final class LogLinePatternParser {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "%(-)?(\\d+)?(?:\\.(\\d+))?"
                    + "(d|p|level|t|thread|c|logger|C|loggerFull|m|msg|ex|throwable|wEx|n|X)"
                    + "(?:\\{([^}]*)\\})?");

    private static final ConcurrentMap<String, List<PatternToken>> CACHE =
            new ConcurrentHashMap<String, List<PatternToken>>(8);

    private static final ConcurrentMap<String, DateTimeFormatter> DTF_CACHE =
            new ConcurrentHashMap<String, DateTimeFormatter>(4);
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private LogLinePatternParser() {
    }

    public static String format(LogEntry entry, String pattern) {
        if (entry == null) {
            return "";
        }
        String safePattern = (pattern == null || pattern.isEmpty())
                ? LogLineDefaults.getEffectivePattern()
                : pattern;

        List<PatternToken> tokens = CACHE.computeIfAbsent(safePattern, LogLinePatternParser::compile);
        return render(entry, tokens);
    }

    public static void invalidateCache() {
        CACHE.clear();
        DTF_CACHE.clear();
    }

    private static List<PatternToken> compile(String pattern) {
        List<PatternToken> tokens = new ArrayList<PatternToken>();
        Matcher matcher = TOKEN_PATTERN.matcher(pattern);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                tokens.add(new LiteralToken(pattern.substring(lastEnd, matcher.start())));
            }

            boolean leftAlign = "-".equals(matcher.group(1));
            int minWidth = parseIntOrZero(matcher.group(2));
            int maxWidth = parseIntOrZero(matcher.group(3));
            String conversion = matcher.group(4);
            String argument = matcher.group(5);
            tokens.add(new PlaceholderToken(conversion, argument, leftAlign, minWidth, maxWidth));
            lastEnd = matcher.end();
        }

        if (lastEnd < pattern.length()) {
            tokens.add(new LiteralToken(pattern.substring(lastEnd)));
        }

        return java.util.Collections.unmodifiableList(tokens);
    }

    private static String render(LogEntry entry, List<PatternToken> tokens) {
        StringBuilder sb = new StringBuilder(256);
        for (PatternToken token : tokens) {
            token.appendTo(sb, entry);
        }
        return sb.toString();
    }

    private static int parseIntOrZero(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    interface PatternToken {
        void appendTo(StringBuilder sb, LogEntry entry);
    }

    private static final class LiteralToken implements PatternToken {
        private final String text;

        private LiteralToken(String text) {
            this.text = text;
        }

        @Override
        public void appendTo(StringBuilder sb, LogEntry entry) {
            sb.append(text);
        }
    }

    private static final class PlaceholderToken implements PatternToken {
        private final String conversion;
        private final String argument;
        private final boolean leftAlign;
        private final int minWidth;
        private final int maxWidth;

        private PlaceholderToken(String conversion,
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
        public void appendTo(StringBuilder sb, LogEntry entry) {
            String value = resolveValue(entry);
            if (value == null) {
                value = "";
            }
            applyWidth(sb, value);
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
                case "X":
                    return resolveMdc(entry);
                default:
                    return "%" + conversion;
            }
        }

        private String resolveMdc(LogEntry entry) {
            Map<String, String> mdc = entry.getMdcContext();
            if (mdc == null || mdc.isEmpty()) {
                return "";
            }
            if (argument == null || argument.isEmpty()) {
                return mdc.toString();
            }
            String value = mdc.get(argument);
            return value == null ? "" : value;
        }

        private String formatTime(LogEntry entry) {
            String datePattern = (argument != null && !argument.isEmpty())
                    ? argument
                    : "yyyy-MM-dd HH:mm:ss.SSS";
            try {
                if ("yyyy-MM-dd HH:mm:ss.SSS".equals(datePattern)) {
                    return CachedTimestampFormatter.format(entry.getTimestamp());
                }
                DateTimeFormatter formatter = DTF_CACHE.computeIfAbsent(datePattern, DateTimeFormatter::ofPattern);
                LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.getTimestamp()), SYSTEM_ZONE);
                return dateTime.format(formatter);
            } catch (Exception e) {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.getTimestamp()), SYSTEM_ZONE).toString();
            }
        }

        private String resolveThrowable(LogEntry entry) {
            if (!entry.hasThrowable()) {
                return "";
            }
            return "\n" + entry.getThrowableString();
        }

        private void applyWidth(StringBuilder sb, String value) {
            if (maxWidth > 0 && value.length() > maxWidth) {
                value = value.substring(value.length() - maxWidth);
            }
            if (minWidth > 0 && value.length() < minWidth) {
                int padding = minWidth - value.length();
                if (leftAlign) {
                    sb.append(value);
                    appendSpaces(sb, padding);
                } else {
                    appendSpaces(sb, padding);
                    sb.append(value);
                }
                return;
            }
            sb.append(value);
        }

        private void appendSpaces(StringBuilder sb, int count) {
            for (int i = 0; i < count; i++) {
                sb.append(' ');
            }
        }
    }

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

    private static final class CachedTimestampFormatter {
        private static final DateTimeFormatter BASE_FORMATTER =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        private static final ThreadLocal<CacheEntry> CACHE = ThreadLocal.withInitial(CacheEntry::new);

        private static String format(long epochMillis) {
            CacheEntry cache = CACHE.get();
            long epochSeconds = epochMillis / 1000L;
            int millis = (int) (epochMillis % 1000L);
            if (millis < 0) {
                millis += 1000;
            }
            if (epochSeconds != cache.cachedEpochSeconds) {
                LocalDateTime dateTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(epochSeconds * 1000L), SYSTEM_ZONE);
                cache.cachedBase = dateTime.format(BASE_FORMATTER);
                cache.cachedEpochSeconds = epochSeconds;
            }
            return cache.cachedBase + '.' + padMillis(millis);
        }

        private static String padMillis(int millis) {
            if (millis < 10) {
                return "00" + millis;
            }
            if (millis < 100) {
                return "0" + millis;
            }
            return String.valueOf(millis);
        }
    }

    private static final class CacheEntry {
        private long cachedEpochSeconds = Long.MIN_VALUE;
        private String cachedBase = "";
    }
}
