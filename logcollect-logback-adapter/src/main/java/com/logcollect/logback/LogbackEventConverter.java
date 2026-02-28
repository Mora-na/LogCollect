package com.logcollect.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;

public final class LogbackEventConverter {
    private LogbackEventConverter() {}

    public static String toMessage(ILoggingEvent event) {
        return event == null ? null : event.getFormattedMessage();
    }
}
