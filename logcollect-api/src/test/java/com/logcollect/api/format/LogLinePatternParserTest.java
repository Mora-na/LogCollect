package com.logcollect.api.format;

import com.logcollect.api.model.LogEntry;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogLinePatternParserTest {

    @Test
    void format_standardPattern_allTokensReplaced() {
        LogEntry entry = LogEntry.builder()
                .traceId("trace-1")
                .content("hello world")
                .level("INFO")
                .timestamp(1706745600000L)
                .threadName("main")
                .loggerName("com.example.MyService")
                .build();

        String result = LogLinePatternParser.format(entry, "%d{yyyy-MM-dd HH:mm:ss} %-5p [%t] %c{1} - %m%n");
        assertThat(result)
                .contains("INFO")
                .contains("[main]")
                .contains("MyService")
                .contains("hello world");
    }

    @Test
    void format_withException_includesStackTrace() {
        LogEntry entry = LogEntry.builder()
                .traceId("t")
                .content("error msg")
                .level("ERROR")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("com.A")
                .throwableString("java.lang.NPE\n\tat com.A.b(A.java:1)")
                .build();
        String result = LogLinePatternParser.format(entry, "%m%ex");
        assertThat(result).contains("java.lang.NPE");
    }

    @Test
    void format_withMDC_replacesKeys() {
        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("requestId", "req-123");
        LogEntry entry = LogEntry.builder()
                .traceId("t1")
                .content("msg")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("com.test.A")
                .mdcContext(mdc)
                .build();

        String result = LogLinePatternParser.format(entry, "%X{requestId} %m");
        assertThat(result).contains("req-123");
    }

    @Test
    void format_cachedParsing_samePatternReusesCompiledPath() {
        String pattern = "%d %p %m%n";
        LogEntry entry = LogEntry.builder()
                .traceId("t1")
                .content("msg")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("com.test.A")
                .mdcContext(Collections.emptyMap())
                .build();

        String first = LogLinePatternParser.format(entry, pattern);
        String second = LogLinePatternParser.format(entry, pattern);
        assertThat(second).isEqualTo(first);
    }
}
