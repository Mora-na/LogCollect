package com.logcollect.core.runtime;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.atomic.AtomicBoolean;

public class LogCollectGlobalSwitch {

    private final AtomicBoolean enabled;

    public LogCollectGlobalSwitch(boolean initialEnabled) {
        this.enabled = new AtomicBoolean(initialEnabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public boolean setEnabled(boolean value) {
        boolean previous = enabled.getAndSet(value);
        if (previous != value) {
            LogCollectInternalLogger.info("LogCollect global switch changed: {} -> {}", previous, value);
        }
        return previous;
    }

    public void onConfigChange(boolean newValue) {
        boolean previous = enabled.getAndSet(newValue);
        if (previous != newValue) {
            LogCollectInternalLogger.info("LogCollect global switch changed via config source: {} -> {}",
                    previous, newValue);
        }
    }
}
