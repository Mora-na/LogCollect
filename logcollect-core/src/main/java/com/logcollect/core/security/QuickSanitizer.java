package com.logcollect.core.security;

public final class QuickSanitizer {
    private QuickSanitizer() {
    }

    public static String removeControlChars(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String summarize(String rawMessage, int maxLength) {
        if (rawMessage == null) {
            return null;
        }
        String sanitized = removeControlChars(rawMessage);
        int limit = maxLength <= 0 ? 256 : maxLength;
        if (sanitized.length() <= limit) {
            return sanitized;
        }
        return sanitized.substring(0, limit);
    }
}
