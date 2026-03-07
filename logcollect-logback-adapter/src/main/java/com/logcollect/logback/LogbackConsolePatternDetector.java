package com.logcollect.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.spi.AppenderAttachable;
import com.logcollect.core.format.ConsolePatternDetector;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 从 Logback 控制台 Appender 中检测日志 pattern。
 */
public class LogbackConsolePatternDetector implements ConsolePatternDetector {

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("ch.qos.logback.classic.LoggerContext");
            return LoggerFactory.getILoggerFactory() instanceof LoggerContext;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public String detectRawPattern() {
        if (!isAvailable()) {
            return null;
        }

        try {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger rootLogger = ctx.getLogger(Logger.ROOT_LOGGER_NAME);

            Set<Appender<ILoggingEvent>> visited = new HashSet<Appender<ILoggingEvent>>();
            Iterator<Appender<ILoggingEvent>> it = rootLogger.iteratorForAppenders();
            while (it.hasNext()) {
                String pattern = detectPatternFromAppender(it.next(), visited);
                if (pattern != null && !pattern.isEmpty()) {
                    return pattern;
                }
            }
        } catch (Exception ignored) {
            // 检测失败，返回 null，由调用方使用兜底值
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String detectPatternFromAppender(Appender<ILoggingEvent> appender,
                                             Set<Appender<ILoggingEvent>> visited) {
        if (appender == null || visited == null || !visited.add(appender)) {
            return null;
        }

        if (appender instanceof ConsoleAppender) {
            ConsoleAppender<ILoggingEvent> consoleAppender = (ConsoleAppender<ILoggingEvent>) appender;
            if (consoleAppender.getEncoder() instanceof PatternLayoutEncoder) {
                PatternLayoutEncoder encoder = (PatternLayoutEncoder) consoleAppender.getEncoder();
                return encoder.getPattern();
            }
            return null;
        }

        if (appender instanceof AppenderAttachable) {
            AppenderAttachable<ILoggingEvent> attachable = (AppenderAttachable<ILoggingEvent>) appender;
            Iterator<Appender<ILoggingEvent>> nested = attachable.iteratorForAppenders();
            while (nested != null && nested.hasNext()) {
                String pattern = detectPatternFromAppender(nested.next(), visited);
                if (pattern != null && !pattern.isEmpty()) {
                    return pattern;
                }
            }
        }

        return null;
    }
}
