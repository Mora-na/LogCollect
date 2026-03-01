package com.logcollect.core.security;

import com.logcollect.api.sanitizer.LogSanitizer;

import java.util.regex.Pattern;

/**
 * 默认日志净化器实现。
 */
public class DefaultLogSanitizer implements LogSanitizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B\\[[0-9;]*[a-zA-Z]");
    private static final Pattern MESSAGE_CONTROL_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\r\\n\\t]");
    private static final Pattern THROWABLE_DANGEROUS_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    @Override
    public String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = removeHtmlTags(removeAnsiCodes(raw));
        return MESSAGE_CONTROL_CHARS.matcher(cleaned).replaceAll(" ");
    }

    @Override
    public String sanitizeThrowable(String throwableString) {
        if (throwableString == null) {
            return null;
        }

        String cleaned = removeHtmlTags(removeAnsiCodes(throwableString));
        cleaned = THROWABLE_DANGEROUS_CHARS.matcher(cleaned).replaceAll("");

        String[] lines = cleaned.split("\\n", -1);
        StringBuilder sb = new StringBuilder(cleaned.length() + lines.length * 12);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0 || isStandardStackLine(line)) {
                sb.append(line);
            } else {
                sb.append("\t[ex-msg] ").append(line);
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String removeHtmlTags(String input) {
        return HTML_TAG.matcher(input).replaceAll("");
    }

    private static String removeAnsiCodes(String input) {
        return ANSI_ESCAPE.matcher(input).replaceAll("");
    }

    private static boolean isStandardStackLine(String line) {
        String t = line == null ? "" : line.trim();
        return t.isEmpty()
                || t.startsWith("at ")
                || t.startsWith("Caused by:")
                || t.startsWith("Suppressed:")
                || t.matches("^\\.\\.\\. \\d+ (more|common frames omitted)$");
    }
}
