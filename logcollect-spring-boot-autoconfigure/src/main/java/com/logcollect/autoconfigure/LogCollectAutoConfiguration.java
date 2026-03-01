package com.logcollect.autoconfigure;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.enums.LogFramework;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.autoconfigure.config.LocalPropertiesLogCollectConfigSource;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.config.LogCollectLocalConfigCache;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import com.logcollect.core.security.SecurityComponentRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Configuration
@EnableConfigurationProperties(LogCollectProperties.class)
@PropertySource(name = "logcollect-default", value = "classpath:logcollect-default.properties", ignoreResourceNotFound = true)
public class LogCollectAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LocalPropertiesLogCollectConfigSource localPropertiesLogCollectConfigSource(
            Environment environment) {
        return new LocalPropertiesLogCollectConfigSource(environment);
    }

    @Bean
    public LogCollectLocalConfigCache logCollectLocalConfigCache(LogCollectProperties props) {
        LogCollectProperties.LocalCache localCache = props.getConfig().getLocalCache();
        if (localCache == null || !localCache.isEnabled()) {
            return null;
        }
        return new LogCollectLocalConfigCache(Paths.get(localCache.getPath()), localCache.getMaxAgeDays());
    }

    @Bean
    public LogCollectConfigResolver logCollectConfigResolver(
            ObjectProvider<List<LogCollectConfigSource>> sourcesProvider,
            LogCollectLocalConfigCache cache,
            LogCollectGlobalSwitch globalSwitch,
            ObjectProvider<LogCollectMetrics> metricsProvider) {
        List<LogCollectConfigSource> sources = sourcesProvider.getIfAvailable(ArrayList::new);
        sources.sort(Comparator.comparingInt(LogCollectConfigSource::getOrder));
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                sources, cache, globalSwitch, metricsProvider.getIfAvailable());
        for (LogCollectConfigSource source : sources) {
            try {
                source.addChangeListener((java.util.function.Consumer<String>)
                        sourceName -> resolver.onConfigChange(sourceName));
            } catch (Throwable t) {
                LogCollectInternalLogger.warn("Register config change listener failed: {}", source.getType(), t);
            }
        }
        return resolver;
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return new CircuitBreakerRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public LogCollectGlobalSwitch logCollectGlobalSwitch(LogCollectProperties props) {
        return new LogCollectGlobalSwitch(props == null || props.getGlobal() == null || props.getGlobal().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalBufferMemoryManager logCollectGlobalBufferMemoryManager(
            LogCollectProperties props,
            ObjectProvider<LogCollectMetrics> metricsProvider) {
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(
                props.getGlobal().getBuffer().getTotalMaxBytesValue());
        Object metrics = metricsProvider.getIfAvailable();
        if (metrics != null) {
            manager.setMetrics(metrics);
        }
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityComponentRegistry securityComponentRegistry(ApplicationContext applicationContext) {
        return new SecurityComponentRegistry(applicationContext);
    }

    @Bean
    public LogCollectInternalLogger.Level logCollectInternalLoggerLevel(LogCollectProperties props) {
        try {
            LogCollectInternalLogger.Level level = LogCollectInternalLogger.Level.valueOf(
                    props.getInternal().getLogLevel().toUpperCase());
            LogCollectInternalLogger.setLevel(level);
            return level;
        } catch (IllegalArgumentException e) {
            LogCollectInternalLogger.setLevel(LogCollectInternalLogger.Level.INFO);
            return LogCollectInternalLogger.Level.INFO;
        }
    }

    @Bean
    public org.springframework.beans.factory.InitializingBean logCollectLogFrameworkValidator(LogCollectProperties properties) {
        return () -> {
            LogFramework framework = properties.getGlobal().getLogFramework();
            if (framework == LogFramework.LOGBACK && !isPresent("ch.qos.logback.classic.LoggerContext")) {
                throw new IllegalStateException(
                        "logFramework=LOGBACK but Logback is not on classpath. Add logback-classic or set logFramework=AUTO");
            }
            if (framework == LogFramework.LOG4J2 && !isPresent("org.apache.logging.log4j.core.LoggerContext")) {
                throw new IllegalStateException(
                        "logFramework=LOG4J2 but Log4j2 is not on classpath. Add log4j-core or set logFramework=AUTO");
            }
        };
    }

    private boolean isPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
