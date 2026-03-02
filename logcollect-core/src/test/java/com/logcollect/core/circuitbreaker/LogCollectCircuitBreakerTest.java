package com.logcollect.core.circuitbreaker;

import com.logcollect.api.model.LogCollectConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LogCollectCircuitBreakerTest {

    @Test
    void shouldOpenWhenSlidingWindowFailureRateReached() {
        LogCollectConfig config = baseConfig(5, 0.6d);
        LogCollectCircuitBreaker breaker = new LogCollectCircuitBreaker(() -> config);

        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();

        Assertions.assertEquals(LogCollectCircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    void shouldStayClosedWhenFailureRateBelowThreshold() {
        LogCollectConfig config = baseConfig(5, 0.8d);
        LogCollectCircuitBreaker breaker = new LogCollectCircuitBreaker(() -> config);

        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();

        Assertions.assertEquals(LogCollectCircuitBreaker.State.CLOSED, breaker.getState());
    }

    private LogCollectConfig baseConfig(int windowSize, double failureRateThreshold) {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setEnableDegrade(true);
        config.setDegradeWindowSize(windowSize);
        config.setDegradeFailThreshold(windowSize);
        config.setDegradeFailureRateThreshold(failureRateThreshold);
        config.setRecoverIntervalSeconds(1);
        config.setRecoverMaxIntervalSeconds(2);
        config.setHalfOpenPassCount(1);
        config.setHalfOpenSuccessThreshold(1);
        return config;
    }
}
