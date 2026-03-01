package com.logcollect.core.format;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatternValidator {

    private static final int MAX_PATTERN_LENGTH = 1024;
    private static final Pattern SPEC_PATTERN = Pattern.compile("%(\\-?\\d*\\.?\\d*)([a-zA-Z]+)");

    private static final Set<String> KNOWN_SPECIFIERS = new HashSet<String>(Arrays.asList(
            "d", "p", "level", "t", "thread", "c", "logger",
            "C", "loggerFull", "m", "msg", "ex", "throwable", "wEx", "n", "X"
    ));

    private PatternValidator() {
    }

    public static String validateAndClean(String pattern) {
        if (pattern == null) {
            return null;
        }
        String trimmed = pattern.trim();
        if (trimmed.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException(
                    "Pattern length " + trimmed.length() + " exceeds max " + MAX_PATTERN_LENGTH);
        }
        Matcher matcher = SPEC_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            String spec = matcher.group(2);
            if (!KNOWN_SPECIFIERS.contains(spec)) {
                throw new IllegalArgumentException("Unknown pattern specifier: %" + spec);
            }
        }
        return trimmed;
    }
}
