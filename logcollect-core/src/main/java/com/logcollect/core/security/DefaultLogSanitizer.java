package com.logcollect.core.security;

import com.logcollect.api.sanitizer.LogSanitizer;

import java.util.regex.Pattern;

public class DefaultLogSanitizer implements LogSanitizer {
    private static final Pattern CRLF = Pattern.compile("[\\r\\n]");
    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;]*m");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    @Override
    public String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String result = raw;
        result = CRLF.matcher(result).replaceAll(" ");
        result = HTML.matcher(result).replaceAll("");
        result = ANSI.matcher(result).replaceAll("");
        result = CONTROL_CHARS.matcher(result).replaceAll("");
        return result;
    }
}
