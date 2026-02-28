package com.logcollect.log4j2;

import org.apache.logging.log4j.core.LogEvent;

public final class Log4j2EventConverter {
    private Log4j2EventConverter() {}

    public static String toMessage(LogEvent event) {
        return event == null ? null : event.getMessage().getFormattedMessage();
    }
}
