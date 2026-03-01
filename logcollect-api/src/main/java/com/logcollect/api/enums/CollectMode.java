package com.logcollect.api.enums;

public enum CollectMode {
    AUTO,
    SINGLE,
    AGGREGATE;

    public CollectMode resolve() {
        return this == AUTO ? AGGREGATE : this;
    }
}
