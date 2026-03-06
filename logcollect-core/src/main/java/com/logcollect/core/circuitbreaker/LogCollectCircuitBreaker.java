package com.logcollect.core.circuitbreaker;

import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Lock-free circuit breaker with decaying counters.
 */
public class LogCollectCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static final int DEFAULT_FAIL_THRESHOLD = 5;
    private static final long DEFAULT_RECOVER_INTERVAL_MS = 30_000L;
    private static final long DEFAULT_MAX_RECOVER_INTERVAL_MS = 300_000L;
    private static final int DEFAULT_HALF_OPEN_PASS_COUNT = 3;
    private static final int DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD = 3;
    private static final int DEFAULT_WINDOW_SIZE = 10;
    private static final double DEFAULT_FAILURE_RATE_THRESHOLD = 0.6d;
    private static final double DECAY_FACTOR = 0.5d;

    private static final AtomicLongFieldUpdater<LogCollectCircuitBreaker> LAST_DECAY_NANOS_UPDATER =
            AtomicLongFieldUpdater.newUpdater(LogCollectCircuitBreaker.class, "lastDecayNanos");

    private final AtomicReference<State> state = new AtomicReference<State>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenPassedCount = new AtomicInteger(0);

    private final AtomicLong failureCount = new AtomicLong(0L);
    private final AtomicLong successCount = new AtomicLong(0L);
    private final AtomicLong totalCount = new AtomicLong(0L);

    private final AtomicLong lastFailureTime = new AtomicLong(0L);
    private final AtomicLong currentRecoverIntervalMs = new AtomicLong(DEFAULT_RECOVER_INTERVAL_MS);

    private final Supplier<LogCollectConfig> configSupplier;
    private volatile Runnable recoveryCallback;
    private volatile long lastDecayNanos = 0L;

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
            long interval = currentRecoverIntervalMs.get();
            if (elapsed >= interval) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenSuccessCount.set(0);
                    halfOpenPassedCount.set(0);
                    LogCollectInternalLogger.info("CircuitBreaker -> HALF_OPEN");
                    return true;
                }
                current = state.get();
                if (current == State.HALF_OPEN) {
                    return allowHalfOpen(config);
                }
            }
            return false;
        }

        return allowHalfOpen(config);
    }

    public void recordSuccess() {
        LogCollectConfig config = safeConfig();
        maybeDecay(config);

        successCount.incrementAndGet();
        totalCount.incrementAndGet();
        consecutiveFailures.set(0);

        if (state.get() == State.HALF_OPEN) {
            int success = halfOpenSuccessCount.incrementAndGet();
            if (success >= config.getHalfOpenSuccessThreshold()) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    halfOpenSuccessCount.set(0);
                    halfOpenPassedCount.set(0);
                    currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
                    resetCounters();
                    LogCollectInternalLogger.info("CircuitBreaker -> CLOSED");
                    Runnable callback = recoveryCallback;
                    if (callback != null) {
                        try {
                            callback.run();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    public void recordFailure() {
        LogCollectConfig config = safeConfig();
        maybeDecay(config);

        long now = System.currentTimeMillis();
        lastFailureTime.set(now);

        long failures = failureCount.incrementAndGet();
        long total = totalCount.incrementAndGet();
        consecutiveFailures.incrementAndGet();

        State current = state.get();
        if (current == State.CLOSED) {
            int minSamples = Math.max(1, config.getDegradeFailThreshold());
            double threshold = normalizeFailureRateThreshold(config);
            double failureRate = total <= 0L ? 0.0d : ((double) failures / (double) total);
            if (total >= minSamples && failureRate >= threshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
                    LogCollectInternalLogger.warn(
                            "CircuitBreaker -> OPEN, failureRate={}({}/{}), threshold={}",
                            String.format("%.2f", failureRate),
                            Long.valueOf(failures),
                            Long.valueOf(total),
                            Double.valueOf(threshold));
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
        LogCollectConfig config = safeConfig();
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
        halfOpenSuccessCount.set(0);
        halfOpenPassedCount.set(0);
        currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
        lastFailureTime.set(0L);
        resetCounters();
    }

    public void forceOpen() {
        LogCollectConfig config = safeConfig();
        state.set(State.OPEN);
        lastFailureTime.set(System.currentTimeMillis());
        halfOpenSuccessCount.set(0);
        halfOpenPassedCount.set(0);
        currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
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

    private void maybeDecay(LogCollectConfig config) {
        long intervalNanos = resolveDecayIntervalNanos(config);
        long now = System.nanoTime();
        long last = lastDecayNanos;
        if (now - last < intervalNanos) {
            return;
        }
        if (!LAST_DECAY_NANOS_UPDATER.compareAndSet(this, last, now)) {
            return;
        }
        decayCounter(failureCount);
        decayCounter(successCount);
        decayCounter(totalCount);
    }

    private void decayCounter(AtomicLong counter) {
        long current;
        long decayed;
        do {
            current = counter.get();
            decayed = (long) (current * DECAY_FACTOR);
        } while (!counter.compareAndSet(current, decayed));
    }

    private void resetCounters() {
        failureCount.set(0L);
        successCount.set(0L);
        totalCount.set(0L);
        LAST_DECAY_NANOS_UPDATER.set(this, 0L);
    }

    private long resolveDecayIntervalNanos(LogCollectConfig config) {
        int configuredSeconds = config.getDegradeDecayIntervalSeconds();
        if (configuredSeconds > 0) {
            return TimeUnit.SECONDS.toNanos(configuredSeconds);
        }
        int window = Math.max(1, config.getDegradeWindowSize());
        int autoSeconds = Math.max(5, Math.min(30, window / 2));
        return TimeUnit.SECONDS.toNanos(autoSeconds);
    }

    private double normalizeFailureRateThreshold(LogCollectConfig config) {
        double threshold = config.getDegradeFailureRateThreshold();
        if (threshold <= 0.0d) {
            return DEFAULT_FAILURE_RATE_THRESHOLD;
        }
        if (threshold > 1.0d) {
            return 1.0d;
        }
        return threshold;
    }

    private long applyJitter(long baseMs) {
        double jitter = 1.0d + (ThreadLocalRandom.current().nextDouble() * 0.4d - 0.2d);
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
                config.setRecoverIntervalSeconds((int) (DEFAULT_RECOVER_INTERVAL_MS / 1000L));
            }
            if (config.getRecoverMaxIntervalSeconds() <= 0) {
                config.setRecoverMaxIntervalSeconds((int) (DEFAULT_MAX_RECOVER_INTERVAL_MS / 1000L));
            }
            if (config.getHalfOpenPassCount() <= 0) {
                config.setHalfOpenPassCount(DEFAULT_HALF_OPEN_PASS_COUNT);
            }
            if (config.getHalfOpenSuccessThreshold() <= 0) {
                config.setHalfOpenSuccessThreshold(DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD);
            }
            if (config.getDegradeWindowSize() <= 0) {
                config.setDegradeWindowSize(DEFAULT_WINDOW_SIZE);
            }
            if (config.getDegradeFailureRateThreshold() <= 0.0d) {
                config.setDegradeFailureRateThreshold(DEFAULT_FAILURE_RATE_THRESHOLD);
            } else if (config.getDegradeFailureRateThreshold() > 1.0d) {
                config.setDegradeFailureRateThreshold(1.0d);
            }
            return config;
        } catch (Exception ignored) {
            LogCollectConfig defaults = LogCollectConfig.frameworkDefaults();
            defaults.setDegradeFailThreshold(DEFAULT_FAIL_THRESHOLD);
            defaults.setRecoverIntervalSeconds((int) (DEFAULT_RECOVER_INTERVAL_MS / 1000L));
            defaults.setRecoverMaxIntervalSeconds((int) (DEFAULT_MAX_RECOVER_INTERVAL_MS / 1000L));
            defaults.setHalfOpenPassCount(DEFAULT_HALF_OPEN_PASS_COUNT);
            defaults.setHalfOpenSuccessThreshold(DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD);
            defaults.setDegradeWindowSize(DEFAULT_WINDOW_SIZE);
            defaults.setDegradeFailureRateThreshold(DEFAULT_FAILURE_RATE_THRESHOLD);
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
