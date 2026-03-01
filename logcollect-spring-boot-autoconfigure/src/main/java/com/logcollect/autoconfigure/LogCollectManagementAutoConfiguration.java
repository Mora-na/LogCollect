package com.logcollect.autoconfigure;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.autoconfigure.metrics.LogCollectManagementEndpoint;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.degrade.DegradeFileManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@ConditionalOnBean(LogCollectConfigResolver.class)
@ConditionalOnProperty(prefix = "logcollect.management", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogCollectManagementAutoConfiguration {
    @Bean
    public LogCollectManagementEndpoint logCollectManagementEndpoint(
            CircuitBreakerRegistry circuitBreakerRegistry,
            LogCollectConfigResolver configResolver,
            @Autowired(required = false) List<LogCollectConfigSource> configSources,
            @Autowired(required = false) DegradeFileManager degradeFileManager,
            @Autowired(required = false) GlobalBufferMemoryManager bufferMemoryManager,
            @Qualifier("logCollectGlobalEnabled") AtomicBoolean globalEnabled,
            @Autowired(required = false) LogCollectMetrics metrics) {
        return new LogCollectManagementEndpoint(
                circuitBreakerRegistry,
                configResolver,
                configSources,
                degradeFileManager,
                bufferMemoryManager,
                globalEnabled,
                metrics);
    }
}
