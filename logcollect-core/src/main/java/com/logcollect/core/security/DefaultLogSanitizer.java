package com.logcollect.core.security;

import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.api.sanitizer.SanitizeResult;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 默认日志净化器实现。
 */
public class DefaultLogSanitizer implements LogSanitizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<script[^>]*>.*?</script>");
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
        if (!containsAnsiEscape(raw) && !containsHtmlMarker(raw) && !containsMessageControl(raw)) {
            return raw;
        }
        String cleaned = raw;
        if (containsAnsiEscape(cleaned)) {
            cleaned = removeAnsiCodes(cleaned);
        }
        if (containsHtmlMarker(cleaned)) {
            cleaned = removeHtmlTags(cleaned);
        }
        if (containsMessageControl(cleaned)) {
            cleaned = MESSAGE_CONTROL_CHARS.matcher(cleaned).replaceAll(" ");
        }
        return cleaned;
    }

    @Override
    public String sanitizeThrowable(String throwableString) {
        if (throwableString == null) {
            return null;
        }
        boolean hasLineBreak = containsLineBreak(throwableString);
        boolean needsCleanup = containsAnsiEscape(throwableString)
                || containsHtmlMarker(throwableString)
                || containsThrowableDangerousControl(throwableString);
        if (!needsCleanup && !hasLineBreak) {
            return throwableString;
        }

        String cleaned = throwableString;
        if (containsAnsiEscape(cleaned)) {
            cleaned = removeAnsiCodes(cleaned);
        }
        if (containsHtmlMarker(cleaned)) {
            cleaned = removeHtmlTags(cleaned);
        }
        if (containsThrowableDangerousControl(cleaned)) {
            cleaned = THROWABLE_DANGEROUS_CHARS.matcher(cleaned).replaceAll("");
        }
        if (!containsLineBreak(cleaned)) {
            return cleaned;
        }

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

    @Override
    public SanitizeResult sanitizeWithStats(String raw) {
        String value = sanitize(raw);
        return new SanitizeResult(value, !Objects.equals(raw, value));
    }

    private static String removeHtmlTags(String input) {
        String noScript = SCRIPT_BLOCK.matcher(input).replaceAll("");
        return HTML_TAG.matcher(noScript).replaceAll("");
    }

    private static String removeAnsiCodes(String input) {
        return ANSI_ESCAPE.matcher(input).replaceAll("");
    }

    private static boolean containsAnsiEscape(String input) {
        return input.indexOf('\u001B') >= 0;
    }

    private static boolean containsHtmlMarker(String input) {
        return input.indexOf('<') >= 0 && input.indexOf('>') >= 0;
    }

    private static boolean containsLineBreak(String input) {
        return input.indexOf('\n') >= 0;
    }

    private static boolean containsMessageControl(String input) {
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c <= 0x1F || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsThrowableDangerousControl(String input) {
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            if ((c >= 0x00 && c <= 0x08) || c == 0x0B || c == 0x0C || (c >= 0x0E && c <= 0x1F) || c == 0x7F) {
                return true;
            }
        }
        return false;
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
