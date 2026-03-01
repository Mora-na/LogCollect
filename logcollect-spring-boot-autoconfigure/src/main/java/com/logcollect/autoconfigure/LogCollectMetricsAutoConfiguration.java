package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnProperty(name = "logcollect.global.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class LogCollectMetricsAutoConfiguration {
    @Bean
    public LogCollectMetrics logCollectMetrics(MeterRegistry registry, LogCollectProperties properties) {
        return new LogCollectMetrics(registry, properties);
    }
}
