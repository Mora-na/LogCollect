package com.logcollect.autoconfigure;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.enums.LogFramework;
import com.logcollect.api.format.LogLineDefaults;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.LogEntry;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.autoconfigure.config.LocalPropertiesLogCollectConfigSource;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.core.buffer.AsyncFlushExecutor;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.config.LogCollectLocalConfigCache;
import com.logcollect.core.diagnostics.LogCollectDiag;
import com.logcollect.core.format.ConsolePatternDetector;
import com.logcollect.core.format.PatternCleaner;
import com.logcollect.core.format.PatternValidator;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
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
            ObjectProvider<GlobalBufferMemoryManager> globalBufferMemoryManagerProvider,
            ObjectProvider<LogCollectMetrics> metricsProvider) {
        List<LogCollectConfigSource> sources = sourcesProvider.getIfAvailable(ArrayList::new);
        sources.sort(Comparator.comparingInt(LogCollectConfigSource::getOrder));
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                sources, cache, globalSwitch, metricsProvider.getIfAvailable(),
                globalBufferMemoryManagerProvider.getIfAvailable());
        for (LogCollectConfigSource source : sources) {
            try {
                source.addChangeListener((sourceName, changedProperties) ->
                        resolver.onConfigChange(sourceName, changedProperties));
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
        LogCollectProperties.Buffer buffer = props.getGlobal().getBuffer();
        GlobalBufferMemoryManager.CounterMode counterMode =
                GlobalBufferMemoryManager.CounterMode.from(buffer.getCounterMode());
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(
                buffer.getTotalMaxBytesValue(),
                counterMode,
                buffer.getHardCeilingBytesValue());
        com.logcollect.api.metrics.LogCollectMetrics metrics = metricsProvider.getIfAvailable();
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
    @ConditionalOnMissingBean
    public LogCollectBufferRegistry logCollectBufferRegistry() {
        return new LogCollectBufferRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public LogCollectLifecycle logCollectLifecycle(LogCollectBufferRegistry registry,
                                                   LogCollectGlobalSwitch globalSwitch,
                                                   ObjectProvider<LogCollectMetrics> metricsProvider) {
        return new LogCollectLifecycle(registry, globalSwitch, metricsProvider.getIfAvailable(), 15_000L);
    }

    @Bean
    public org.springframework.beans.factory.InitializingBean logCollectEstimationFactorInitializer(LogCollectProperties props) {
        return () -> {
            if (props == null || props.getGlobal() == null || props.getGlobal().getBuffer() == null) {
                return;
            }
            double factor = props.getGlobal().getBuffer().getEstimationFactor();
            LogEntry.setEstimationFactor(factor);
        };
    }

    @Bean
    @ConditionalOnMissingBean(LogSanitizer.class)
    public LogSanitizer logSanitizer() {
        return new DefaultLogSanitizer();
    }

    @Bean
    @ConditionalOnMissingBean(LogMasker.class)
    public LogMasker logMasker() {
        return new DefaultLogMasker();
    }

    @Bean
    public org.springframework.beans.factory.InitializingBean logCollectConsolePatternInitializer(
            ObjectProvider<java.util.List<ConsolePatternDetector>> detectorsProvider,
            LogCollectConfigResolver configResolver) {
        return () -> {
            java.util.List<ConsolePatternDetector> detectors = detectorsProvider.getIfAvailable(ArrayList::new);
            ConsolePatternInitializer.initialize(detectors);
            applyConfiguredLogLinePattern(configResolver);
        };
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
    public org.springframework.beans.factory.InitializingBean logCollectDiagInitializer(LogCollectProperties props) {
        return () -> LogCollectDiag.setEnabled(props != null && props.isDebug());
    }

    @Bean
    public org.springframework.beans.factory.InitializingBean logCollectAsyncFlushExecutorInitializer(LogCollectProperties props) {
        return () -> {
            if (props == null || props.getGlobal() == null || props.getGlobal().getFlush() == null) {
                return;
            }
            LogCollectProperties.Flush flush = props.getGlobal().getFlush();
            AsyncFlushExecutor.configure(
                    flush.getCoreThreads(),
                    flush.getMaxThreads(),
                    flush.getQueueCapacity());
        };
    }

    @Bean
    public LogCollectConfigValidator logCollectConfigValidator(
            LogCollectConfigResolver resolver,
            ObjectProvider<GlobalBufferMemoryManager> globalBufferManagerProvider) {
        return new LogCollectConfigValidator(resolver, globalBufferManagerProvider.getIfAvailable());
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

    private void applyConfiguredLogLinePattern(LogCollectConfigResolver configResolver) {
        if (configResolver == null) {
            return;
        }
        try {
            java.util.Map<String, String> globals = configResolver.getLatestGlobalProperties();
            if (globals == null || globals.isEmpty()) {
                return;
            }
            String configuredPattern = globals.get("format.log-line-pattern");
            if (configuredPattern != null && !configuredPattern.trim().isEmpty()) {
                String cleaned = PatternCleaner.clean(configuredPattern);
                String validated = PatternValidator.validateAndClean(cleaned);
                LogLineDefaults.setDetectedPattern(validated);
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.debug("Apply configured log-line-pattern failed: {}", t.getMessage());
        }
    }
}
