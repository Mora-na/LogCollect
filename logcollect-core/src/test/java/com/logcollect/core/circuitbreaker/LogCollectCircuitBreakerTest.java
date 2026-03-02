package com.logcollect.core.circuitbreaker;

import com.logcollect.api.model.LogCollectConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LogCollectCircuitBreakerTest {

    @Test
    void initialState_isClosed() {
        LogCollectCircuitBreaker cb = createDefaultBreaker();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.CLOSED);
        assertThat(cb.allowWrite()).isTrue();
    }

    @Test
    void closed_consecutiveFailures_transitionsToOpen() {
        LogCollectCircuitBreaker cb = createBreaker(3, 5, 0.6, 30, 300, 3, 3);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.OPEN);
        assertThat(cb.allowWrite()).isFalse();
    }

    @Test
    void closed_failureRateBelowThreshold_staysClosed() {
        LogCollectCircuitBreaker cb = createBreaker(3, 10, 0.6, 30, 300, 3, 3);
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.CLOSED);
    }

    @Test
    void open_afterRecoverInterval_transitionsToHalfOpen() {
        LogCollectCircuitBreaker cb = createBreaker(3, 5, 0.6, 1, 5, 3, 3);
        triggerOpen(cb);
        await().atMost(Duration.ofSeconds(3)).until(cb::allowWrite);
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void open_beforeRecoverInterval_staysOpen() {
        LogCollectCircuitBreaker cb = createBreaker(3, 5, 0.6, 60, 300, 3, 3);
        triggerOpen(cb);
        assertThat(cb.allowWrite()).isFalse();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.OPEN);
    }

    @Test
    void halfOpen_consecutiveSuccesses_transitionsToClosed() {
        LogCollectCircuitBreaker cb = createBreaker(3, 5, 0.6, 1, 5, 3, 3);
        triggerOpen(cb);
        waitForHalfOpen(cb);
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpen_failure_backToOpenWithLongerInterval() {
        LogCollectCircuitBreaker cb = createBreaker(3, 5, 0.6, 1, 5, 3, 3);
        triggerOpen(cb);
        long firstInterval = cb.getCurrentRecoverInterval();
        waitForHalfOpen(cb);
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.OPEN);
        assertThat(cb.getCurrentRecoverInterval()).isGreaterThan(firstInterval);
    }

    @Test
    void open_exponentialBackoff_cappedAtMax() {
        LogCollectCircuitBreaker cb = createBreaker(3, 5, 0.6, 1, 5, 3, 3);
        for (int i = 0; i < 8; i++) {
            triggerOpen(cb);
            waitForHalfOpen(cb);
            cb.recordFailure();
        }
        assertThat(cb.getCurrentRecoverInterval()).isLessThanOrEqualTo(5000);
    }

    @Test
    void reset_fromOpen_goesToClosed() {
        LogCollectCircuitBreaker cb = createDefaultBreaker();
        triggerOpen(cb);
        cb.manualReset();
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.CLOSED);
        assertThat(cb.allowWrite()).isTrue();
    }

    @Test
    void recordFailure_concurrent_stateConsistent() throws Exception {
        LogCollectCircuitBreaker cb = createBreaker(100, 200, 0.6, 30, 300, 3, 3);
        com.logcollect.core.test.ConcurrentTestHelper.runConcurrently(10, () -> {
            for (int i = 0; i < 50; i++) {
                cb.recordFailure();
            }
        }, Duration.ofSeconds(5));
        assertThat(cb.getState()).isEqualTo(LogCollectCircuitBreaker.State.OPEN);
    }

    private LogCollectCircuitBreaker createDefaultBreaker() {
        return createBreaker(5, 10, 0.6, 30, 300, 3, 3);
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
    }

    private void waitForHalfOpen(LogCollectCircuitBreaker cb) {
        await().atMost(Duration.ofSeconds(8)).until(() -> {
            cb.allowWrite();
            return cb.getState() == LogCollectCircuitBreaker.State.HALF_OPEN;
        });
    }
}
