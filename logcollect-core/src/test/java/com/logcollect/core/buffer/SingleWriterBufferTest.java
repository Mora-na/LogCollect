package com.logcollect.core.buffer;

import com.logcollect.api.model.LogEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SingleWriterBufferTest {

    @Test
    void offer_shouldUpdateCounters() {
        SingleWriterBuffer buffer = new SingleWriterBuffer(100, 1024 * 1024, 16);
        LogEntry entry = entry("test");
        long bytes = entry.estimateBytes();

        buffer.offer(entry, bytes);

        assertThat(buffer.getCount()).isEqualTo(1);
        assertThat(buffer.getBytes()).isEqualTo(bytes);
        assertThat(buffer.getTotalCollected()).isEqualTo(1);
    }

    @Test
    void drain_shouldResetCountAndBytes() {
        SingleWriterBuffer buffer = new SingleWriterBuffer(100, 1024 * 1024, 16);
        long total = 0L;
        for (int i = 0; i < 10; i++) {
            LogEntry entry = entry("msg-" + i);
            long bytes = entry.estimateBytes();
            total += bytes;
            buffer.offer(entry, bytes);
        }

        List<LogEntry> drained = buffer.drain(new ArrayList<LogEntry>());

        assertThat(drained).hasSize(10);
        assertThat(buffer.getCount()).isZero();
        assertThat(buffer.getBytes()).isZero();
        assertThat(buffer.getTotalBytes()).isEqualTo(total);
    }

    @Test
    void closeFlags_shouldBeVisible() {
        SingleWriterBuffer buffer = new SingleWriterBuffer(10, 0, 4);
        buffer.markClosing();
        buffer.markClosed();
        assertThat(buffer.isClosing()).isTrue();
        assertThat(buffer.isClosed()).isTrue();
    }

    private LogEntry entry(String content) {
        return LogEntry.builder()
                .traceId("trace")
                .content(content)
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("main")
                .loggerName("Test")
                .build();
    }
}
