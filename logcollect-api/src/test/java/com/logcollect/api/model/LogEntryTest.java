package com.logcollect.api.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogEntryTest {

    @Test
    void builder_allFields_immutable() {
        LogEntry entry = LogEntry.builder()
                .traceId("t1")
                .content("msg")
                .level("INFO")
                .timestamp(123L)
                .threadName("main")
                .loggerName("c.t.A")
                .throwableString("ex")
                .mdcContext(Collections.singletonMap("k", "v"))
                .build();

        assertThat(entry.getTraceId()).isEqualTo("t1");
        assertThat(entry.hasThrowable()).isTrue();
        assertThat(entry.estimateBytes()).isGreaterThan(0);
        assertThatThrownBy(() -> entry.getMdcContext().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getTime_derivesFromTimestamp() {
        long ts = System.currentTimeMillis();
        LogEntry entry = LogEntry.builder()
                .traceId("t")
                .content("m")
                .level("I")
                .timestamp(ts)
                .threadName("t")
                .loggerName("l")
                .build();
        assertThat(entry.getTime()).isNotNull();
    }

    @Test
    void estimateBytes_consistentEstimation() {
        LogEntry small = LogEntry.builder()
                .traceId("t")
                .content("x")
                .level("I")
                .timestamp(0)
                .threadName("t")
                .loggerName("l")
                .build();
        LogEntry large = LogEntry.builder()
                .traceId("t")
                .content(repeat("x", 10000))
                .level("I")
                .timestamp(0)
                .threadName("t")
                .loggerName("l")
                .build();
        assertThat(large.estimateBytes()).isGreaterThan(small.estimateBytes());
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
