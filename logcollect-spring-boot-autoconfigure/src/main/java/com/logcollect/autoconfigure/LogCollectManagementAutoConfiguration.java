package com.logcollect.autoconfigure;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.autoconfigure.management.LogCollectManagementEndpoint;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.degrade.DegradeFileManager;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
            LogCollectGlobalSwitch globalSwitch,
            @Autowired(required = false) LogCollectMetrics metrics) {
        return new LogCollectManagementEndpoint(
                circuitBreakerRegistry,
                configResolver,
                configSources,
                degradeFileManager,
                bufferMemoryManager,
                globalSwitch,
                metrics);
    }
}
