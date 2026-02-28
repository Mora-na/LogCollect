package com.logcollect.autoconfigure;

import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.log4j2.LogCollectLog4j2Appender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConditionalOnClass({LoggerContext.class, LogCollectLog4j2Appender.class})
@ConditionalOnProperty(prefix = "logcollect", name = {"enabled", "logging.auto-register-appender"},
        havingValue = "true", matchIfMissing = true)
public class LogCollectLog4j2AppenderAutoConfiguration implements InitializingBean {

    private static final String DEFAULT_APPENDER_NAME = "LOG_COLLECT";

    private final LogCollectProperties properties;

    public LogCollectLog4j2AppenderAutoConfiguration(LogCollectProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
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
            org.apache.logging.log4j.spi.LoggerContext ctx = LogManager.getContext(false);
            if (!(ctx instanceof LoggerContext)) {
                return;
            }

            LoggerContext context = (LoggerContext) ctx;
            org.apache.logging.log4j.core.config.Configuration log4jConfiguration = context.getConfiguration();
            if (log4jConfiguration == null) {
                return;
            }

            String appenderName = resolveAppenderName();
            Appender existingByName = log4jConfiguration.getAppender(appenderName);
            if (existingByName != null && !(existingByName instanceof LogCollectLog4j2Appender)) {
                LogCollectInternalLogger.warn(
                        "Appender name '{}' already exists and is not LogCollectLog4j2Appender, skip auto registration.",
                        appenderName);
                return;
            }

            Appender logCollectAppender = findLogCollectAppender(log4jConfiguration.getAppenders());
            if (logCollectAppender == null) {
                LogCollectLog4j2Appender created = LogCollectLog4j2Appender.createAppender(appenderName, null);
                if (created == null) {
                    LogCollectInternalLogger.warn("Create LogCollect Log4j2 appender failed.");
                    return;
                }
                created.start();
                log4jConfiguration.addAppender(created);
                logCollectAppender = created;
            }

            LoggerConfig rootLogger = log4jConfiguration.getRootLogger();
            if (!rootLogger.getAppenders().containsKey(logCollectAppender.getName())) {
                rootLogger.addAppender(logCollectAppender, null, null);
                context.updateLoggers();
                LogCollectInternalLogger.info("Auto-registered LogCollect Log4j2 appender '{}' on ROOT logger.",
                        logCollectAppender.getName());
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Auto-register LogCollect Log4j2 appender failed.", t);
        }
    }

    private Appender findLogCollectAppender(Map<String, Appender> appenders) {
        if (appenders == null || appenders.isEmpty()) {
            return null;
        }
        for (Appender appender : appenders.values()) {
            if (appender instanceof LogCollectLog4j2Appender) {
                return appender;
            }
        }
        return null;
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
