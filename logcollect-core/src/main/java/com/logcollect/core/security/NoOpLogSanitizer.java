package com.logcollect.core.security;

import com.logcollect.api.sanitizer.LogSanitizer;

public final class NoOpLogSanitizer implements LogSanitizer {
    public static final NoOpLogSanitizer INSTANCE = new NoOpLogSanitizer();

    private NoOpLogSanitizer() {
    }

    @Override
    public String sanitize(String raw) {
        return raw;
    }
}
