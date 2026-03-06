package com.logcollect.core.pipeline;

import com.logcollect.core.util.SpinWaitHint;

import java.util.concurrent.locks.LockSupport;

/**
 * Three-level adaptive idle strategy: spin, then yield, then short park.
 */
public final class AdaptiveIdleStrategy {

    private static final long PARK_NANOS = 100_000L;

    private int idleCount = 0;

    public void idle(int spinThreshold, int yieldThreshold) {
        idle(spinThreshold, yieldThreshold, null);
    }

    public void idle(int spinThreshold, int yieldThreshold, Runnable parkAction) {
        idleCount++;
        int spin = Math.max(1, spinThreshold);
        int yield = Math.max(spin + 1, yieldThreshold);
        if (idleCount <= spin) {
            SpinWaitHint.onSpinWait();
            return;
        }
        if (idleCount <= yield) {
            Thread.yield();
            return;
        }
        if (parkAction != null) {
            parkAction.run();
        } else {
            LockSupport.parkNanos(PARK_NANOS);
        }
    }

    public void reset() {
        idleCount = 0;
    }
}
