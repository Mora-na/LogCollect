package com.logcollect.core.circuitbreaker;

import com.logcollect.api.model.LogCollectConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectCircuitBreakerAdditionalTest {

    @Test
    void halfOpen_passCountLimited() throws Exception {
        LogCollectCircuitBreaker cb = createBreaker(3, 10, 0.6, 1, 300, 2, 3);
        triggerOpen(cb);

        Thread.sleep(1200L);
        assertThat(cb.allowWrite()).isTrue();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.HALF_OPEN);
        assertThat(cb.allowWrite()).isTrue();
        assertThat(cb.allowWrite()).isTrue();
        assertThat(cb.allowWrite()).isFalse();
    }

    @Test
    void halfOpen_partialSuccess_belowThreshold_staysHalfOpen() throws Exception {
        LogCollectCircuitBreaker cb = createBreaker(3, 10, 0.6, 1, 300, 3, 3);
        triggerOpen(cb);

        Thread.sleep(1200L);
        assertThat(cb.allowWrite()).isTrue();
        cb.recordSuccess();
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void manualReset_recoversInitialInterval() throws Exception {
        LogCollectCircuitBreaker cb = createBreaker(3, 10, 0.6, 1, 300, 3, 3);
        triggerOpen(cb);

        Thread.sleep(1200L);
        assertThat(cb.allowWrite()).isTrue();
        cb.recordFailure();
        long increased = cb.getCurrentRecoverInterval();
        assertThat(increased).isGreaterThan(1000L);

        cb.manualReset();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.CLOSED);
        assertThat(cb.getCurrentRecoverInterval()).isEqualTo(1000L);
    }

    @Test
    void closed_mixedResultBelowFailureRate_staysClosed() {
        LogCollectCircuitBreaker cb = createBreaker(5, 10, 0.6, 30, 300, 3, 3);
        for (int i = 0; i < 6; i++) {
            cb.recordSuccess();
        }
        for (int i = 0; i < 4; i++) {
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.CLOSED);
    }

    @Test
    void safeConfig_supplierThrows_fallsBackToDefaults() {
        LogCollectCircuitBreaker cb = new LogCollectCircuitBreaker(() -> {
            throw new IllegalStateException("boom");
        });
        assertThat(cb.allowWrite()).isTrue();
        cb.recordFailure();
        assertThat(cb.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void allowWrite_whenDegradeDisabled_alwaysTrue() {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setEnableDegrade(false);
        LogCollectCircuitBreaker cb = new LogCollectCircuitBreaker(() -> config);
        cb.forceOpen();
        assertThat(cb.allowWrite()).isTrue();
    }

    @Test
    void recoveryCallback_invokedWhenHalfOpenCloses() throws Exception {
        LogCollectCircuitBreaker cb = createBreaker(3, 10, 0.6, 1, 300, 2, 2);
        AtomicBoolean recovered = new AtomicBoolean(false);
        cb.setRecoveryCallback(() -> recovered.set(true));
        triggerOpen(cb);
        Thread.sleep(1200L);
        assertThat(cb.allowWrite()).isTrue();
        cb.recordSuccess();
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.CLOSED);
        assertThat(recovered.get()).isTrue();
        assertThat(cb.getLastFailureTimeFormatted()).isNotBlank();
    }

    private LogCollectCircuitBreaker createBreaker(int failThreshold,
                                                   int windowSize,
                                                   double failureRate,
                                                   int recoverSec,
                                                   int maxRecoverSec,
                                                   int halfOpenPassCount,
                                                   int halfOpenSuccessThreshold) {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setEnableDegrade(true);
        config.setDegradeFailThreshold(failThreshold);
        config.setDegradeWindowSize(windowSize);
        config.setDegradeFailureRateThreshold(failureRate);
        config.setRecoverIntervalSeconds(recoverSec);
        config.setRecoverMaxIntervalSeconds(maxRecoverSec);
        config.setHalfOpenPassCount(halfOpenPassCount);
        config.setHalfOpenSuccessThreshold(halfOpenSuccessThreshold);
        return new LogCollectCircuitBreaker(() -> config);
    }

    private void triggerOpen(LogCollectCircuitBreaker cb) {
        for (int i = 0; i < 100; i++) {
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.OPEN);
    }
}
