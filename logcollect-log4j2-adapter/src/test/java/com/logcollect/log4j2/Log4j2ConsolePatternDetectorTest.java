package com.logcollect.log4j2;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Log4j2ConsolePatternDetectorTest {

    @Test
    void detectPatternFromAppender_traversesAsyncAppenderRefs() throws Exception {
        Configuration configuration = new DefaultConfiguration();
        String pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %msg%n";

        PatternLayout layout = PatternLayout.newBuilder()
                .withConfiguration(configuration)
                .withPattern(pattern)
                .build();

        ConsoleAppender console = ConsoleAppender.newBuilder()
                .setName("CONSOLE")
                .setConfiguration(configuration)
                .setLayout(layout)
                .build();
        console.start();
        configuration.addAppender(console);

        AsyncAppender async = AsyncAppender.newBuilder()
                .setName("ASYNC_CONSOLE")
                .setConfiguration(configuration)
                .setAppenderRefs(new AppenderRef[]{AppenderRef.createAppenderRef("CONSOLE", null, null)})
                .build();
        async.start();
        configuration.addAppender(async);

        Log4j2ConsolePatternDetector detector = new Log4j2ConsolePatternDetector();
        String detected = (String) invoke(
                detector,
                "detectPatternFromAppender",
                new Class[]{Appender.class, Configuration.class, Set.class},
                async,
                configuration,
                new HashSet<Appender>());

        assertThat(detected).isEqualTo(pattern);

        async.stop();
        console.stop();
    }

    private Object invoke(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}

