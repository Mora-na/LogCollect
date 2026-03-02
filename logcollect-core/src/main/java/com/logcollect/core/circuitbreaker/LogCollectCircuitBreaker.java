package com.logcollect.core.circuitbreaker;

import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/**
 * 日志降级写入熔断器。
 *
 * <p>当 CLOSED 状态下滑动窗口失败率达到阈值时进入 OPEN，
 * 恢复窗口到期后进入 HALF_OPEN 试探写入，连续成功达到阈值后回到 CLOSED。
 */
public class LogCollectCircuitBreaker {
    /**
     * 熔断状态。
     */
    public enum State {
        /** 正常关闭态，允许写入。 */
        CLOSED,
        /** 打开态，拒绝写入。 */
        OPEN,
        /** 半开态，按配额允许少量探测写入。 */
        HALF_OPEN
    }

    private static final int DEFAULT_FAIL_THRESHOLD = 5;
    private static final long DEFAULT_RECOVER_INTERVAL_MS = 30_000L;
    private static final long DEFAULT_MAX_RECOVER_INTERVAL_MS = 300_000L;
    private static final int DEFAULT_HALF_OPEN_PASS_COUNT = 3;
    private static final int DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD = 3;
    private static final int DEFAULT_WINDOW_SIZE = 10;
    private static final double DEFAULT_FAILURE_RATE_THRESHOLD = 0.6d;

    private static final class RequestResult {
        private final boolean success;
        private final long timestamp;

        private RequestResult(boolean success, long timestamp) {
            this.success = success;
            this.timestamp = timestamp;
        }
    }

    private static final class WindowSnapshot {
        private final int total;
        private final int failures;
        private final double failureRate;

        private WindowSnapshot(int total, int failures) {
            this.total = total;
            this.failures = failures;
            this.failureRate = total <= 0 ? 0.0d : ((double) failures / (double) total);
        }
    }

    private final AtomicReference<State> state = new AtomicReference<State>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenPassedCount = new AtomicInteger(0);
    private final AtomicInteger windowIndex = new AtomicInteger(0);
    private volatile AtomicReferenceArray<RequestResult> window =
            new AtomicReferenceArray<RequestResult>(DEFAULT_WINDOW_SIZE);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong currentRecoverIntervalMs = new AtomicLong(DEFAULT_RECOVER_INTERVAL_MS);

    private final Supplier<LogCollectConfig> configSupplier;
    private volatile Runnable recoveryCallback;

    /**
     * 创建熔断器。
     *
     * @param configSupplier 配置提供者；返回 null 时使用框架默认配置
     */
    public LogCollectCircuitBreaker(Supplier<LogCollectConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    /**
     * 判断当前是否允许写入降级存储。
     *
     * @return true 表示允许写入
     */
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

    /**
     * 记录一次写入成功事件。
     */
    public void recordSuccess() {
        LogCollectConfig config = safeConfig();
        ensureWindowSize(config);

        State current = state.get();
        if (current == State.CLOSED) {
            consecutiveFailures.set(0);
            recordWindowResult(true);
            return;
        }

        if (current == State.HALF_OPEN) {
            int success = halfOpenSuccessCount.incrementAndGet();
            if (success >= config.getHalfOpenSuccessThreshold()) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    consecutiveFailures.set(0);
                    halfOpenSuccessCount.set(0);
                    halfOpenPassedCount.set(0);
                    currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
                    resetWindow();
                    LogCollectInternalLogger.info("CircuitBreaker -> CLOSED");
                    Runnable callback = recoveryCallback;
                    if (callback != null) {
                        try {
                            callback.run();
                        } catch (Exception ignored) {
                        } catch (Error e) {
                            throw e;
                        }
                    }
                }
            }
        }
    }

    /**
     * 记录一次写入失败事件。
     */
    public void recordFailure() {
        LogCollectConfig config = safeConfig();
        ensureWindowSize(config);
        long now = System.currentTimeMillis();
        lastFailureTime.set(now);

        State current = state.get();
        if (current == State.CLOSED) {
            int failures = consecutiveFailures.incrementAndGet();
            recordWindowResult(false);
            WindowSnapshot snapshot = snapshotWindow();
            int minSamples = Math.max(1, config.getDegradeFailThreshold());
            if (snapshot.total >= minSamples && snapshot.failureRate >= normalizeFailureRateThreshold(config)) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
                    LogCollectInternalLogger.warn(
                            "CircuitBreaker -> OPEN, failureRate={}({}/{}), threshold={}",
                            String.format("%.2f", snapshot.failureRate),
                            snapshot.failures,
                            snapshot.total,
                            normalizeFailureRateThreshold(config));
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

    /**
     * 手动重置熔断器到 CLOSED。
     */
    public void manualReset() {
        LogCollectConfig config = safeConfig();
        ensureWindowSize(config);
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
        halfOpenSuccessCount.set(0);
        halfOpenPassedCount.set(0);
        currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
        lastFailureTime.set(0);
        resetWindow();
    }

    /**
     * 立即强制打开熔断器。
     *
     * <p>用于边界层捕获到 {@link Error} 时快速熔断，避免系统处于不确定状态继续写入。
     */
    public void forceOpen() {
        LogCollectConfig config = safeConfig();
        ensureWindowSize(config);
        state.set(State.OPEN);
        lastFailureTime.set(System.currentTimeMillis());
        halfOpenSuccessCount.set(0);
        halfOpenPassedCount.set(0);
        currentRecoverIntervalMs.set(initialRecoverIntervalMs(config));
    }

    /**
     * 获取当前熔断状态。
     *
     * @return 当前状态
     */
    public State getState() {
        return state.get();
    }

    /**
     * 获取连续失败次数（兼容指标，CLOSED 阶段统计）。
     *
     * @return 连续失败数
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * 获取最近一次失败时间戳。
     *
     * @return Unix 毫秒时间戳；0 表示无记录
     */
    public long getLastFailureTime() {
        return lastFailureTime.get();
    }

    /**
     * 获取最近一次失败时间的可读字符串。
     *
     * @return ISO-8601 时间字符串；无记录时返回 {@code never}
     */
    public String getLastFailureTimeFormatted() {
        long ts = lastFailureTime.get();
        return ts == 0 ? "never" : java.time.Instant.ofEpochMilli(ts).toString();
    }

    /**
     * 获取当前恢复间隔。
     *
     * @return 当前恢复间隔（毫秒）
     */
    public long getCurrentRecoverInterval() {
        return currentRecoverIntervalMs.get();
    }

    /**
     * 设置恢复回调（HALF_OPEN 成功恢复到 CLOSED 时触发）。
     *
     * @param recoveryCallback 回调函数，可为 null
     */
    public void setRecoveryCallback(Runnable recoveryCallback) {
        this.recoveryCallback = recoveryCallback;
    }

    private boolean allowHalfOpen(LogCollectConfig config) {
        int passed = halfOpenPassedCount.incrementAndGet();
        return passed <= config.getHalfOpenPassCount();
    }

    private void ensureWindowSize(LogCollectConfig config) {
        int required = Math.max(1, config.getDegradeWindowSize());
        AtomicReferenceArray<RequestResult> current = window;
        if (current.length() == required) {
            return;
        }
        synchronized (this) {
            AtomicReferenceArray<RequestResult> latest = window;
            if (latest.length() != required) {
                window = new AtomicReferenceArray<RequestResult>(required);
                windowIndex.set(0);
            }
        }
    }

    private void recordWindowResult(boolean success) {
        AtomicReferenceArray<RequestResult> current = window;
        int slot = Math.floorMod(windowIndex.getAndIncrement(), current.length());
        current.set(slot, new RequestResult(success, System.currentTimeMillis()));
    }

    private WindowSnapshot snapshotWindow() {
        AtomicReferenceArray<RequestResult> current = window;
        int total = 0;
        int failures = 0;
        for (int i = 0; i < current.length(); i++) {
            RequestResult result = current.get(i);
            if (result == null) {
                continue;
            }
            total++;
            if (!result.success) {
                failures++;
            }
        }
        return new WindowSnapshot(total, failures);
    }

    private void resetWindow() {
        AtomicReferenceArray<RequestResult> current = window;
        for (int i = 0; i < current.length(); i++) {
            current.set(i, null);
        }
        windowIndex.set(0);
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
            defaults.setRecoverIntervalSeconds((int) (DEFAULT_RECOVER_INTERVAL_MS / 1000));
            defaults.setRecoverMaxIntervalSeconds((int) (DEFAULT_MAX_RECOVER_INTERVAL_MS / 1000));
            defaults.setHalfOpenPassCount(DEFAULT_HALF_OPEN_PASS_COUNT);
            defaults.setHalfOpenSuccessThreshold(DEFAULT_HALF_OPEN_SUCCESS_THRESHOLD);
            defaults.setDegradeWindowSize(DEFAULT_WINDOW_SIZE);
            defaults.setDegradeFailureRateThreshold(DEFAULT_FAILURE_RATE_THRESHOLD);
            return defaults;
        } catch (Error e) {
            throw e;
        }
    }

    private long initialRecoverIntervalMs(LogCollectConfig config) {
        return Math.max(1L, config.getRecoverIntervalSeconds() * 1000L);
    }

    private long maxRecoverIntervalMs(LogCollectConfig config) {
        return Math.max(initialRecoverIntervalMs(config), config.getRecoverMaxIntervalSeconds() * 1000L);
    }
}
