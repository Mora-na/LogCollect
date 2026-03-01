package com.logcollect.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.logcollect.api.enums.LogFramework;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.security.SecurityComponentRegistry;
import com.logcollect.logback.LogCollectLogbackAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

import java.util.Iterator;

@Configuration
@ConditionalOnClass({LoggerContext.class, LogCollectLogbackAppender.class})
public class LogCollectLogbackAppenderAutoConfiguration implements InitializingBean {

    private static final String DEFAULT_APPENDER_NAME = "LOG_COLLECT";

    private final LogCollectProperties properties;
    private final LogCollectMetrics metrics;
    private final SecurityComponentRegistry securityRegistry;

    public LogCollectLogbackAppenderAutoConfiguration(LogCollectProperties properties,
                                                      org.springframework.beans.factory.ObjectProvider<LogCollectMetrics> metricsProvider,
                                                      org.springframework.beans.factory.ObjectProvider<SecurityComponentRegistry> securityRegistryProvider) {
        this.properties = properties;
        this.metrics = metricsProvider.getIfAvailable();
        this.securityRegistry = securityRegistryProvider.getIfAvailable();
    }

    @Override
    public void afterPropertiesSet() {
        if (properties != null && properties.getGlobal().getLogFramework() == LogFramework.LOG4J2) {
            return;
        }
        if (properties != null
                && properties.getLogging() != null
                && !properties.getLogging().isAutoRegisterAppender()) {
            LogCollectInternalLogger.info("Auto registration of logging appender is disabled.");
            return;
        }
        registerAppender();
    }

    private void registerAppender() {
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext)) {
                return;
            }

            LoggerContext context = (LoggerContext) factory;
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            String appenderName = resolveAppenderName();

            Appender<ILoggingEvent> appenderByName = root.getAppender(appenderName);
            if (appenderByName != null) {
                if (appenderByName instanceof LogCollectLogbackAppender) {
                    ((LogCollectLogbackAppender) appenderByName).setSecurityRegistry(securityRegistry);
                    ((LogCollectLogbackAppender) appenderByName).setMetrics(metrics);
                    return;
                }
                LogCollectInternalLogger.warn(
                        "Appender name '{}' already exists and is not LogCollectLogbackAppender, skip auto registration.",
                        appenderName);
                return;
            }

            if (hasLogCollectAppender(root)) {
                return;
            }

            LogCollectLogbackAppender appender = new LogCollectLogbackAppender();
            appender.setName(appenderName);
            appender.setContext(context);
            appender.setSecurityRegistry(securityRegistry);
            appender.setMetrics(metrics);
            appender.start();
            root.addAppender(appender);
            LogCollectInternalLogger.info("Auto-registered LogCollect Logback appender '{}' on ROOT logger.",
                    appenderName);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Auto-register LogCollect Logback appender failed.", t);
        }
    }

    private boolean hasLogCollectAppender(Logger root) {
        Iterator<Appender<ILoggingEvent>> iterator = root.iteratorForAppenders();
        while (iterator.hasNext()) {
            Appender<ILoggingEvent> existing = iterator.next();
            if (existing instanceof LogCollectLogbackAppender) {
                return true;
            }
        }
        return false;
    }

    private String resolveAppenderName() {
        if (properties == null || properties.getLogging() == null) {
            return DEFAULT_APPENDER_NAME;
        }
        String configured = properties.getLogging().getAppenderName();
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_APPENDER_NAME;
        }
        return configured.trim();
    }
}
