package com.logcollect.core.circuitbreaker;

import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class LogCollectCircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state = new AtomicReference<State>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong currentRecoverInterval = new AtomicLong(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger windowFailureCount = new AtomicInteger(0);
    private final Supplier<LogCollectConfig> configSupplier;

    public LogCollectCircuitBreaker(Supplier<LogCollectConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public boolean allowWrite() {
        State s = state.get();
        LogCollectConfig config = configSupplier.get();
        if (s == State.CLOSED) {
            return true;
        }
        if (s == State.OPEN) {
            long now = System.currentTimeMillis();
            long interval = currentRecoverInterval.get();
            if (interval <= 0) {
                interval = config.getRecoverIntervalSeconds() * 1000L;
                currentRecoverInterval.set(interval);
            }
            long jitter = ThreadLocalRandom.current().nextLong(0, 200);
            if (now - lastFailureTime.get() >= interval + jitter) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenSuccesses.set(0);
                }
            }
            return false;
        }
        int passCount = config.getHalfOpenPassCount();
        return halfOpenSuccesses.get() < passCount;
    }

    public void recordSuccess() {
        State s = state.get();
        if (s == State.CLOSED) {
            consecutiveFailures.set(0);
            windowFailureCount.set(0);
            windowStartTime.set(System.currentTimeMillis());
            return;
        }
        if (s == State.HALF_OPEN) {
            int successes = halfOpenSuccesses.incrementAndGet();
            if (successes >= configSupplier.get().getHalfOpenSuccessThreshold()) {
                state.set(State.CLOSED);
                consecutiveFailures.set(0);
                windowFailureCount.set(0);
                currentRecoverInterval.set(0);
            }
        }
    }

    public void recordFailure() {
        LogCollectConfig config = configSupplier.get();
        State s = state.get();
        long now = System.currentTimeMillis();
        if (s == State.CLOSED) {
            long windowMs = config.getDegradeWindowSeconds() * 1000L;
            if (now - windowStartTime.get() > windowMs) {
                windowStartTime.set(now);
                windowFailureCount.set(0);
            }
            int windowFailures = windowFailureCount.incrementAndGet();
            int consecutive = consecutiveFailures.incrementAndGet();
            if (windowFailures >= config.getFailureThreshold() || consecutive >= config.getFailureThreshold()) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    lastFailureTime.set(now);
                    currentRecoverInterval.set(config.getRecoverIntervalSeconds() * 1000L);
                    LogCollectInternalLogger.warn("Circuit opened by failures");
                }
            }
            return;
        }
        if (s == State.HALF_OPEN) {
            state.set(State.OPEN);
            lastFailureTime.set(now);
            long interval = currentRecoverInterval.get();
            if (interval <= 0) {
                interval = config.getRecoverIntervalSeconds() * 1000L;
            }
            long next = Math.min(interval * 2, config.getMaxRecoverIntervalSeconds() * 1000L);
            currentRecoverInterval.set(next);
        }
    }

    public void manualReset() {
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
        windowFailureCount.set(0);
        halfOpenSuccesses.set(0);
        currentRecoverInterval.set(configSupplier.get().getRecoverIntervalSeconds() * 1000L);
    }

    public State getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public String getLastFailureTimeFormatted() {
        long ts = lastFailureTime.get();
        return ts == 0 ? "never" : Instant.ofEpochMilli(ts).toString();
    }

    public long getCurrentRecoverInterval() {
        return currentRecoverInterval.get();
    }
}
