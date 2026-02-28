package com.logcollect.core.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LogCollectInternalLogger {
    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static final Logger LOGGER = LoggerFactory.getLogger("com.logcollect.internal");
    private static volatile Level minLevel = Level.INFO;

    private LogCollectInternalLogger() {}

    public static void setLevel(Level level) {
        if (level != null) {
            minLevel = level;
        }
    }

    public static void debug(String msg, Object... args) {
        if (minLevel.compareTo(Level.DEBUG) <= 0) {
            LOGGER.debug(msg, args);
        }
    }

    public static void info(String msg, Object... args) {
        if (minLevel.compareTo(Level.INFO) <= 0) {
            LOGGER.info(msg, args);
        }
    }

    public static void warn(String msg, Object... args) {
        if (minLevel.compareTo(Level.WARN) <= 0) {
            LOGGER.warn(msg, args);
        }
    }

    public static void error(String msg, Object... args) {
        LOGGER.error(msg, args);
    }
}
