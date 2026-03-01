package com.logcollect.api.security;

import com.logcollect.api.sanitizer.LogSanitizer;

import java.util.regex.Pattern;

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
        String result = raw;
        result = HTML_TAG.matcher(result).replaceAll("");
        result = ANSI_ESCAPE.matcher(result).replaceAll("");
        result = MESSAGE_CONTROL_CHARS.matcher(result).replaceAll(" ");
        return result;
    }

    @Override
    public String sanitizeThrowable(String throwableString) {
        if (throwableString == null) {
            return null;
        }
        String result = throwableString;
        result = HTML_TAG.matcher(result).replaceAll("");
        result = ANSI_ESCAPE.matcher(result).replaceAll("");
        result = THROWABLE_DANGEROUS_CHARS.matcher(result).replaceAll("");
        return result;
    }
}
