package com.logcollect.api.sanitizer;

public final class SanitizeResult {
    private final String value;
    private final boolean modified;

    public SanitizeResult(String value, boolean modified) {
        this.value = value;
        this.modified = modified;
    }

    public String getValue() {
        return value;
    }

    public boolean wasModified() {
        return modified;
    }
}
