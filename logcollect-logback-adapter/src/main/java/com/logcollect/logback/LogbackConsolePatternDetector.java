package com.logcollect.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import com.logcollect.core.format.ConsolePatternDetector;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * 从 Logback 控制台 Appender 中检测日志 pattern。
 */
public class LogbackConsolePatternDetector implements ConsolePatternDetector {

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

            Iterator<Appender<ILoggingEvent>> it = rootLogger.iteratorForAppenders();
            while (it.hasNext()) {
                Appender<ILoggingEvent> appender = it.next();
                if (appender instanceof ConsoleAppender) {
                    ConsoleAppender<ILoggingEvent> consoleAppender = (ConsoleAppender<ILoggingEvent>) appender;
                    if (consoleAppender.getEncoder() instanceof PatternLayoutEncoder) {
                        PatternLayoutEncoder encoder = (PatternLayoutEncoder) consoleAppender.getEncoder();
                        return encoder.getPattern();
                    }
                }
            }
        } catch (Exception ignored) {
            // 检测失败，返回 null，由调用方使用兜底值
        }
        return null;
    }
}
