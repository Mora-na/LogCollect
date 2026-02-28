package com.logcollect.autoconfigure;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.config.LogCollectLocalConfigCache;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
@EnableConfigurationProperties(LogCollectProperties.class)
@ConditionalOnProperty(prefix = "logcollect", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogCollectAutoConfiguration {

    @Bean
    public LogCollectLocalConfigCache logCollectLocalConfigCache(LogCollectProperties props) {
        LogCollectProperties.LocalCache localCache = props.getConfig().getLocalCache();
        if (!localCache.isEnabled()) {
            return null;
        }
        return new LogCollectLocalConfigCache(Paths.get(localCache.getPath()), localCache.getMaxAgeDays());
    }

    @Bean
    public LogCollectConfigResolver logCollectConfigResolver(
            List<LogCollectConfigSource> sources,
            LogCollectLocalConfigCache cache) {
        return new LogCollectConfigResolver(sources, cache);
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return new CircuitBreakerRegistry();
    }

    @Bean
    @Qualifier("logCollectGlobalEnabled")
    @ConditionalOnMissingBean(name = "logCollectGlobalEnabled")
    public AtomicBoolean logCollectGlobalEnabled(LogCollectProperties props) {
        return new AtomicBoolean(props.isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalBufferMemoryManager logCollectGlobalBufferMemoryManager(LogCollectProperties props) {
        return new GlobalBufferMemoryManager(props.getGlobalBufferTotalMaxBytes());
    }

    @Bean
    public LogCollectInternalLogger.Level logCollectInternalLoggerLevel(LogCollectProperties props) {
        try {
            LogCollectInternalLogger.Level level = LogCollectInternalLogger.Level.valueOf(props.getInternalLogLevel());
            LogCollectInternalLogger.setLevel(level);
            return level;
        } catch (IllegalArgumentException e) {
            LogCollectInternalLogger.setLevel(LogCollectInternalLogger.Level.INFO);
            return LogCollectInternalLogger.Level.INFO;
        }
    }
}
