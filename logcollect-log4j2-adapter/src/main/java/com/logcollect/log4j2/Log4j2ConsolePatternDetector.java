package com.logcollect.log4j2;

import com.logcollect.core.format.ConsolePatternDetector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 Log4j2 控制台 Appender 中检测日志 pattern。
 */
public class Log4j2ConsolePatternDetector implements ConsolePatternDetector {

    @Override
    public int getOrder() {
        return 20;
    }

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
            Set<Appender> visited = new HashSet<Appender>();

            for (Appender appender : appenders.values()) {
                String pattern = detectPatternFromAppender(appender, config, visited);
                if (pattern != null && !pattern.isEmpty()) {
                    return pattern;
                }
            }
        } catch (Exception ignored) {
            // 检测失败，返回 null
        }
        return null;
    }

    private String detectPatternFromAppender(Appender appender,
                                             Configuration config,
                                             Set<Appender> visited) {
        if (appender == null || config == null || visited == null || !visited.add(appender)) {
            return null;
        }

        if (appender instanceof ConsoleAppender) {
            ConsoleAppender consoleAppender = (ConsoleAppender) appender;
            if (consoleAppender.getLayout() instanceof PatternLayout) {
                PatternLayout layout = (PatternLayout) consoleAppender.getLayout();
                return layout.getConversionPattern();
            }
            return null;
        }

        if (appender instanceof AsyncAppender) {
            AsyncAppender asyncAppender = (AsyncAppender) appender;

            String[] refNames = asyncAppender.getAppenderRefStrings();
            if (refNames != null) {
                for (String refName : refNames) {
                    if (refName == null || refName.trim().isEmpty()) {
                        continue;
                    }
                    String pattern = detectPatternFromAppender(config.getAppender(refName), config, visited);
                    if (pattern != null && !pattern.isEmpty()) {
                        return pattern;
                    }
                }
            }

            List<Appender> nestedAppenders = asyncAppender.getAppenders();
            if (nestedAppenders != null) {
                for (Appender nested : nestedAppenders) {
                    String pattern = detectPatternFromAppender(nested, config, visited);
                    if (pattern != null && !pattern.isEmpty()) {
                        return pattern;
                    }
                }
            }
        }

        return null;
    }
}
