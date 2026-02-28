package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public class LogCollectMetricsAutoConfiguration {
    @Bean
    public LogCollectMetrics logCollectMetrics(MeterRegistry registry) {
        return new LogCollectMetrics(registry);
    }
}
