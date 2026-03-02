package com.logcollect.core.circuitbreaker;

import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 日志降级写入熔断器。
 *
 * <p>当降级存储连续失败时进入 OPEN，恢复窗口到期后进入 HALF_OPEN 试探写入，
 * 连续成功达到阈值后回到 CLOSED。
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

    private final AtomicReference<State> state = new AtomicReference<State>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenPassedCount = new AtomicInteger(0);
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

    /**
     * 记录一次写入失败事件。
     */
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

    /**
     * 手动重置熔断器到 CLOSED。
     */
    public void manualReset() {
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
        halfOpenSuccessCount.set(0);
        halfOpenPassedCount.set(0);
        currentRecoverIntervalMs.set(initialRecoverIntervalMs(safeConfig()));
        lastFailureTime.set(0);
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
     * 获取连续失败次数（CLOSED 阶段统计）。
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
