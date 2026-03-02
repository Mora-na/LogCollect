package com.logcollect.api.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatedLogTest {

    @Test
    void fields_allAccessible() {
        LocalDateTime now = LocalDateTime.now();
        AggregatedLog agg = new AggregatedLog(
                "flush-id",
                "content",
                10,
                1024,
                "WARN",
                now.minusMinutes(5),
                now,
                true
        );
        assertThat(agg.getFlushId()).isEqualTo("flush-id");
        assertThat(agg.getEntryCount()).isEqualTo(10);
        assertThat(agg.isFinalFlush()).isTrue();
        assertThat(agg.getMaxLevel()).isEqualTo("WARN");
        assertThat(agg.getContent()).isEqualTo("content");
    }
}
