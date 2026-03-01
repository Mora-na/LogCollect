package com.logcollect.core.security;

import com.logcollect.api.masker.LogMasker;

public final class NoOpLogMasker implements LogMasker {
    public static final NoOpLogMasker INSTANCE = new NoOpLogMasker();

    private NoOpLogMasker() {
    }

    @Override
    public String mask(String content) {
        return content;
    }
}
