package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.metrics.LogCollectHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
public class LogCollectHealthAutoConfiguration {
    @Bean
    public LogCollectHealthIndicator logCollectHealthIndicator() {
        return new LogCollectHealthIndicator();
    }
}
