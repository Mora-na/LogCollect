package com.logcollect.core.circuitbreaker;

import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class LogCollectCircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int DEFAULT_FAIL_THRESHOLD = 5;
    private static final long DEFAULT_RECOVER_INTERVAL_MS = 30_000L;
    private static final long DEFAULT_MAX_RECOVER_INTERVAL_MS = 300_000L;
    private static final int DEFAULT_HALF_OPEN_PASS_COUNT = 3;
    private static final int DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD = 3;

    private final AtomicReference<State> state = new AtomicReference<State>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenPassedCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong currentRecoverIntervalMs = new AtomicLong(DEFAULT_RECOVER_INTERVAL_MS);

    private final Supplier<LogCollectConfig> configSupplier;
    private volatile Runnable recoveryCallback;

    public LogCollectCircuitBreaker(Supplier<LogCollectConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public boolean allowWrite() {
        LogCollectConfig config = safeConfig();
        if (!config.isEnableDegrade()) {
            return true;
        }

        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }

        if (current == State.OPEN) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastFailureTime.get();
            long interval = applyJitter(currentRecoverIntervalMs.get());
            if (elapsed >= interval) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenSuccessCount.set(0);
                    halfOpenPassedCount.set(0);
                    LogCollectInternalLogger.info("CircuitBreaker -> HALF_OPEN");
                    return true;
                }
                return allowHalfOpen(config);
            }
            return false;
        }

        return allowHalfOpen(config);
    }

    public void recordSuccess() {
        State current = state.get();
        if (current == State.CLOSED) {
            consecutiveFailures.set(0);
            return;
        }

        if (current == State.HALF_OPEN) {
            int success = halfOpenSuccessCount.incrementAndGet();
            if (success >= safeConfig().getHalfOpenSuccessThreshold()) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    consecutiveFailures.set(0);
                    halfOpenSuccessCount.set(0);
                    halfOpenPassedCount.set(0);
                    currentRecoverIntervalMs.set(initialRecoverIntervalMs(safeConfig()));
                    LogCollectInternalLogger.info("CircuitBreaker -> CLOSED");
                    Runnable callback = recoveryCallback;
                    if (callback != null) {
                        try {
                            callback.run();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    public void recordFailure() {
        LogCollectConfig config = safeConfig();
        long now = System.currentTimeMillis();
        lastFailureTime.set(now);

        State current = state.get();
        if (current == State.CLOSED) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= config.getDegradeFailThreshold()) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
                    LogCollectInternalLogger.warn("CircuitBreaker -> OPEN, failures={}", failures);
                }
            }
            return;
        }

        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                long nextInterval = Math.min(
                        currentRecoverIntervalMs.get() * 2,
                        maxRecoverIntervalMs(config));
                currentRecoverIntervalMs.set(nextInterval);
                halfOpenSuccessCount.set(0);
                halfOpenPassedCount.set(0);
                LogCollectInternalLogger.warn("CircuitBreaker -> OPEN (probe failed), nextInterval={}ms", nextInterval);
            }
        }
    }

    public void manualReset() {
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
        halfOpenSuccessCount.set(0);
        halfOpenPassedCount.set(0);
        currentRecoverIntervalMs.set(initialRecoverIntervalMs(safeConfig()));
        lastFailureTime.set(0);
    }

    public State getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long getLastFailureTime() {
        return lastFailureTime.get();
    }

    public String getLastFailureTimeFormatted() {
        long ts = lastFailureTime.get();
        return ts == 0 ? "never" : java.time.Instant.ofEpochMilli(ts).toString();
    }

    public long getCurrentRecoverInterval() {
        return currentRecoverIntervalMs.get();
    }

    public void setRecoveryCallback(Runnable recoveryCallback) {
        this.recoveryCallback = recoveryCallback;
    }

    private boolean allowHalfOpen(LogCollectConfig config) {
        int passed = halfOpenPassedCount.incrementAndGet();
        return passed <= config.getHalfOpenPassCount();
    }

    private long applyJitter(long baseMs) {
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 0.4 - 0.2);
        return (long) (baseMs * jitter);
    }

    private LogCollectConfig safeConfig() {
        try {
            LogCollectConfig config = configSupplier == null ? null : configSupplier.get();
            if (config == null) {
                config = LogCollectConfig.frameworkDefaults();
            }
            if (config.getDegradeFailThreshold() <= 0) {
                config.setDegradeFailThreshold(DEFAULT_FAIL_THRESHOLD);
            }
            if (config.getRecoverIntervalSeconds() <= 0) {
                config.setRecoverIntervalSeconds((int) (DEFAULT_RECOVER_INTERVAL_MS / 1000));
            }
            if (config.getRecoverMaxIntervalSeconds() <= 0) {
                config.setRecoverMaxIntervalSeconds((int) (DEFAULT_MAX_RECOVER_INTERVAL_MS / 1000));
            }
            if (config.getHalfOpenPassCount() <= 0) {
                config.setHalfOpenPassCount(DEFAULT_HALF_OPEN_PASS_COUNT);
            }
            if (config.getHalfOpenSuccessThreshold() <= 0) {
                config.setHalfOpenSuccessThreshold(DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD);
            }
            return config;
        } catch (Throwable ignored) {
            LogCollectConfig defaults = LogCollectConfig.frameworkDefaults();
            defaults.setDegradeFailThreshold(DEFAULT_FAIL_THRESHOLD);
            defaults.setRecoverIntervalSeconds((int) (DEFAULT_RECOVER_INTERVAL_MS / 1000));
            defaults.setRecoverMaxIntervalSeconds((int) (DEFAULT_MAX_RECOVER_INTERVAL_MS / 1000));
            defaults.setHalfOpenPassCount(DEFAULT_HALF_OPEN_PASS_COUNT);
            defaults.setHalfOpenSuccessThreshold(DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD);
            return defaults;
        }
    }

    private long initialRecoverIntervalMs(LogCollectConfig config) {
        return Math.max(1L, config.getRecoverIntervalSeconds() * 1000L);
    }

    private long maxRecoverIntervalMs(LogCollectConfig config) {
        return Math.max(initialRecoverIntervalMs(config), config.getRecoverMaxIntervalSeconds() * 1000L);
    }
}
