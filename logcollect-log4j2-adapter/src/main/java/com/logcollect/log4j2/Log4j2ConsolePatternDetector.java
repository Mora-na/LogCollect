package com.logcollect.log4j2;

import com.logcollect.core.format.ConsolePatternDetector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.Map;

/**
 * 从 Log4j2 控制台 Appender 中检测日志 pattern。
 */
public class Log4j2ConsolePatternDetector implements ConsolePatternDetector {

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.apache.logging.log4j.core.LoggerContext");
            return LogManager.getContext(false) instanceof LoggerContext;
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
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            Map<String, Appender> appenders = config.getAppenders();

            for (Appender appender : appenders.values()) {
                if (appender instanceof ConsoleAppender) {
                    ConsoleAppender consoleAppender = (ConsoleAppender) appender;
                    if (consoleAppender.getLayout() instanceof PatternLayout) {
                        PatternLayout layout = (PatternLayout) consoleAppender.getLayout();
                        return layout.getConversionPattern();
                    }
                }
            }
        } catch (Exception ignored) {
            // 检测失败，返回 null
        }
        return null;
    }
}
