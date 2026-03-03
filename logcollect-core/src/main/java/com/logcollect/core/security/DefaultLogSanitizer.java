package com.logcollect.core.security;

import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.api.sanitizer.SanitizeResult;

import java.util.Objects;

/**
 * 默认日志净化器实现。
 */
public class DefaultLogSanitizer implements LogSanitizer {

    private static final String SCRIPT_OPEN = "<script";
    private static final String SCRIPT_CLOSE = "</script>";

    @Override
    public String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            return raw;
        }

        ScanFlags flags = scanFlags(raw, true);
        if (!flags.hasAnsiEscape && !flags.hasHtmlMarker && !flags.hasMessageControl) {
            return raw;
        }

        String cleaned = raw;
        if (flags.hasAnsiEscape) {
            cleaned = removeAnsiCodes(cleaned);
        }
        if (flags.hasHtmlMarker) {
            cleaned = removeHtmlTags(cleaned);
        }
        if (flags.hasMessageControl) {
            cleaned = replaceMessageControlWithSpace(cleaned);
        }
        return cleaned;
    }

    @Override
    public String sanitizeThrowable(String throwableString) {
        if (throwableString == null) {
            return null;
        }
        if (throwableString.isEmpty()) {
            return throwableString;
        }

        ScanFlags flags = scanFlags(throwableString, false);
        if (!flags.hasAnsiEscape && !flags.hasHtmlMarker && !flags.hasThrowableDangerousControl && !flags.hasLineBreak) {
            return throwableString;
        }

        String cleaned = throwableString;
        if (flags.hasAnsiEscape) {
            cleaned = removeAnsiCodes(cleaned);
        }
        if (flags.hasHtmlMarker) {
            cleaned = removeHtmlTags(cleaned);
        }
        if (flags.hasThrowableDangerousControl) {
            cleaned = removeThrowableDangerousControl(cleaned);
        }

        if (cleaned.indexOf('\n') < 0) {
            return cleaned;
        }
        return markThrowableInjectedLines(cleaned);
    }

    @Override
    public SanitizeResult sanitizeWithStats(String raw) {
        String value = sanitize(raw);
        return new SanitizeResult(value, !Objects.equals(raw, value));
    }

    private static ScanFlags scanFlags(String input, boolean strictMessageMode) {
        ScanFlags flags = new ScanFlags();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\u001B') {
                flags.hasAnsiEscape = true;
            }
            if (c == '<') {
                flags.hasHtmlMarker = true;
            }
            if (c == '\n') {
                flags.hasLineBreak = true;
            }
            if (strictMessageMode) {
                if (isMessageControl(c)) {
                    flags.hasMessageControl = true;
                }
            } else if (isThrowableDangerousControl(c)) {
                flags.hasThrowableDangerousControl = true;
            }
            if (flags.isComplete(strictMessageMode)) {
                break;
            }
        }
        return flags;
    }

    private static String removeAnsiCodes(String input) {
        StringBuilder out = null;
        int len = input.length();
        int i = 0;
        while (i < len) {
            char c = input.charAt(i);
            if (c == '\u001B' && i + 1 < len && input.charAt(i + 1) == '[') {
                int end = findAnsiSequenceEnd(input, i + 2);
                if (end > 0) {
                    if (out == null) {
                        out = new StringBuilder(len);
                        out.append(input, 0, i);
                    }
                    i = end;
                    continue;
                }
            }
            if (out != null) {
                out.append(c);
            }
            i++;
        }
        return out == null ? input : out.toString();
    }

    private static int findAnsiSequenceEnd(String input, int from) {
        for (int i = from; i < input.length(); i++) {
            char c = input.charAt(i);
            if (isAsciiLetter(c)) {
                return i + 1;
            }
            if (!((c >= '0' && c <= '9') || c == ';')) {
                return -1;
            }
        }
        return -1;
    }

    private static String removeHtmlTags(String input) {
        StringBuilder out = null;
        int len = input.length();
        int i = 0;
        while (i < len) {
            char c = input.charAt(i);
            if (c == '<') {
                if (startsWithIgnoreCase(input, i, SCRIPT_OPEN)) {
                    int closeStart = indexOfIgnoreCase(input, SCRIPT_CLOSE, i + SCRIPT_OPEN.length());
                    if (closeStart >= 0) {
                        if (out == null) {
                            out = new StringBuilder(len);
                            out.append(input, 0, i);
                        }
                        i = closeStart + SCRIPT_CLOSE.length();
                        continue;
                    }
                }
                int tagEnd = input.indexOf('>', i + 1);
                if (tagEnd >= 0) {
                    if (out == null) {
                        out = new StringBuilder(len);
                        out.append(input, 0, i);
                    }
                    i = tagEnd + 1;
                    continue;
                }
            }
            if (out != null) {
                out.append(c);
            }
            i++;
        }
        return out == null ? input : out.toString();
    }

    private static String replaceMessageControlWithSpace(String input) {
        StringBuilder out = null;
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            char normalized = isMessageControl(c) ? ' ' : c;
            if (out != null) {
                out.append(normalized);
            } else if (normalized != c) {
                out = new StringBuilder(len);
                out.append(input, 0, i).append(normalized);
            }
        }
        return out == null ? input : out.toString();
    }

    private static String removeThrowableDangerousControl(String input) {
        StringBuilder out = null;
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (isThrowableDangerousControl(c)) {
                if (out == null) {
                    out = new StringBuilder(len);
                    out.append(input, 0, i);
                }
                continue;
            }
            if (out != null) {
                out.append(c);
            }
        }
        return out == null ? input : out.toString();
    }

    private static String markThrowableInjectedLines(String cleaned) {
        StringBuilder sb = new StringBuilder(cleaned.length() + 16);
        int lineStart = 0;
        int lineIndex = 0;
        int len = cleaned.length();

        for (int i = 0; i <= len; i++) {
            boolean lineEnd = i == len || cleaned.charAt(i) == '\n';
            if (!lineEnd) {
                continue;
            }

            String line = cleaned.substring(lineStart, i);
            if (lineIndex == 0 || isStandardStackLine(line)) {
                sb.append(line);
            } else {
                sb.append("\t[ex-msg] ").append(line);
            }

            if (i < len) {
                sb.append('\n');
            }

            lineStart = i + 1;
            lineIndex++;
        }

        return sb.toString();
    }

    private static boolean isStandardStackLine(String line) {
        if (line == null) {
            return true;
        }

        int start = 0;
        int end = line.length() - 1;
        while (start <= end && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        while (end >= start && Character.isWhitespace(line.charAt(end))) {
            end--;
        }
        if (start > end) {
            return true;
        }

        if (regionStartsWith(line, start, "at ")) {
            return true;
        }
        if (regionStartsWith(line, start, "Caused by:")) {
            return true;
        }
        if (regionStartsWith(line, start, "Suppressed:")) {
            return true;
        }
        return isOmittedFramesLine(line, start, end);
    }

    private static boolean isOmittedFramesLine(String line, int start, int end) {
        if (!regionStartsWith(line, start, "... ")) {
            return false;
        }
        int i = start + 4;
        int digitCount = 0;
        while (i <= end) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            digitCount++;
            i++;
        }
        if (digitCount == 0 || i > end || line.charAt(i) != ' ') {
            return false;
        }
        i++;
        return regionEquals(line, i, end, "more")
                || regionEquals(line, i, end, "common frames omitted");
    }

    private static boolean regionStartsWith(String value, int offset, String prefix) {
        if (offset < 0 || offset + prefix.length() > value.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (value.charAt(offset + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean regionEquals(String value, int start, int end, String expected) {
        int expectedLen = expected.length();
        if (start < 0 || start + expectedLen - 1 != end) {
            return false;
        }
        for (int i = 0; i < expectedLen; i++) {
            if (value.charAt(start + i) != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithIgnoreCase(String value, int offset, String prefix) {
        if (offset < 0 || offset + prefix.length() > value.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char a = value.charAt(offset + i);
            char b = prefix.charAt(i);
            if (a == b) {
                continue;
            }
            if (toLowerAscii(a) != toLowerAscii(b)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfIgnoreCase(String value, String target, int fromIndex) {
        int start = Math.max(0, fromIndex);
        int max = value.length() - target.length();
        for (int i = start; i <= max; i++) {
            if (startsWithIgnoreCase(value, i, target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static char toLowerAscii(char c) {
        if (c >= 'A' && c <= 'Z') {
            return (char) (c + ('a' - 'A'));
        }
        return c;
    }

    private static boolean isMessageControl(char c) {
        return c == '\r' || c == '\n' || c == '\t'
                || (c >= 0x00 && c <= 0x08)
                || c == 0x0B
                || c == 0x0C
                || (c >= 0x0E && c <= 0x1F)
                || c == 0x7F;
    }

    private static boolean isThrowableDangerousControl(char c) {
        if (c == '\n' || c == '\r' || c == '\t') {
            return false;
        }
        return (c >= 0x00 && c <= 0x08)
                || c == 0x0B
                || c == 0x0C
                || (c >= 0x0E && c <= 0x1F)
                || c == 0x7F;
    }

    private static final class ScanFlags {
        private boolean hasAnsiEscape;
        private boolean hasHtmlMarker;
        private boolean hasLineBreak;
        private boolean hasMessageControl;
        private boolean hasThrowableDangerousControl;

        private boolean isComplete(boolean strictMessageMode) {
            if (strictMessageMode) {
                return hasAnsiEscape && hasHtmlMarker && hasMessageControl;
            }
            return hasAnsiEscape && hasHtmlMarker && hasThrowableDangerousControl && hasLineBreak;
        }
    }
}
