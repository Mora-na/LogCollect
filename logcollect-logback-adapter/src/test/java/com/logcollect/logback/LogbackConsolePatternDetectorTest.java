package com.logcollect.logback;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConsolePatternDetectorTest {

    @Test
    void detectPatternFromAppender_traversesAsyncAppender() throws Exception {
        LoggerContext context = new LoggerContext();
        String pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %msg%n";

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(pattern);
        encoder.start();

        ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<ILoggingEvent>();
        console.setContext(context);
        console.setName("CONSOLE");
        console.setEncoder(encoder);
        console.start();

        AsyncAppender async = new AsyncAppender();
        async.setContext(context);
        async.setName("ASYNC_CONSOLE");
        async.addAppender(console);
        async.start();

        LogbackConsolePatternDetector detector = new LogbackConsolePatternDetector();
        String detected = (String) invoke(
                detector,
                "detectPatternFromAppender",
                new Class[]{Appender.class, Set.class},
                async,
                new HashSet<Appender<ILoggingEvent>>());

        assertThat(detected).isEqualTo(pattern);

        async.stop();
        console.stop();
        encoder.stop();
        context.stop();
    }

    private Object invoke(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}

