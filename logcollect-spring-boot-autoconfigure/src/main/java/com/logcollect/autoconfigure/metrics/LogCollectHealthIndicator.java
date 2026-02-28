package com.logcollect.autoconfigure.metrics;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class LogCollectHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.up().withDetail("status", "UP").build();
    }
}
