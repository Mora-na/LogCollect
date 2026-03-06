package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PipelineCoverageGapTest {

    @Test
    void mutableProcessedLogRecord_shouldCoverProcessEstimateAndNullMdcBranch() {
        MutableRawLogRecord raw = new MutableRawLogRecord();
        Map<String, String> mdc = new LinkedHashMap<String, String>();
        mdc.put("k", "v");
        raw.populate("hello", "INFO", "logger", "worker", 123L, "trace-1", null, mdc);

        MutableProcessedLogRecord processed = new MutableProcessedLogRecord();
        SecurityPipeline pipeline = new SecurityPipeline(null, null);
        processed.processFrom(raw, pipeline, SecurityPipeline.SecurityMetrics.NOOP, Long.MAX_VALUE);

        assertThat(processed.getProcessedMessage()).isEqualTo("hello");
        assertThat(processed.getLevel()).isEqualTo("INFO");
        assertThat(processed.getLoggerName()).isEqualTo("logger");
        assertThat(processed.getThreadName()).isEqualTo("worker");
        assertThat(processed.getTimestamp()).isEqualTo(123L);
        assertThat(processed.getTraceId()).isEqualTo("trace-1");
        assertThat(processed.getMdcContext()).containsEntry("k", "v");
        assertThat(processed.isFastPathHit()).isTrue();
        assertThat(processed.estimateBytes()).isGreaterThan(0L);

        processed.mdcContext = null;
        LogEntry withoutMdc = processed.toLogEntry();
        assertThat(withoutMdc.getMdcContext()).isEmpty();
    }

    @Test
    void adaptiveIdleStrategy_twoArgOverload_shouldReachDefaultParkBranch() {
        AdaptiveIdleStrategy strategy = new AdaptiveIdleStrategy();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            strategy.idle(0, 0);
            strategy.idle(0, 0);
            strategy.idle(0, 0);
            strategy.reset();
        });
    }

    @Test
    void pipelineRingBuffer_shouldCoverOverflowAndHelperBranches() {
        PipelineRingBuffer ringBuffer = new PipelineRingBuffer(2, 2);
        assertThat(ringBuffer.capacity()).isEqualTo(2);
        assertThat(ringBuffer.producerSequence()).isZero();
        assertThat(ringBuffer.consumerSequence()).isZero();
        assertThat(ringBuffer.signalIfWaiting()).isFalse();
        assertThat(ringBuffer.hasAvailable()).isFalse();
        assertThat(ringBuffer.availableCount()).isZero();

        long first = ringBuffer.tryClaim();
        long second = ringBuffer.tryClaim();
        assertThat(first).isZero();
        assertThat(second).isEqualTo(1L);
        assertThat(ringBuffer.tryClaim()).isEqualTo(-1L);
        assertThat(ringBuffer.hasPending()).isTrue();
        assertThat(ringBuffer.isPublished(second)).isFalse();

        ringBuffer.publish(first);
        assertThat(ringBuffer.hasAvailable()).isTrue();
        assertThat(ringBuffer.isPublished(first)).isTrue();
        assertThat(ringBuffer.availableCount()).isEqualTo(1);

        ringBuffer.advanceConsumerBy(0);
        ringBuffer.advanceConsumerBy(-3);
        ringBuffer.advanceConsumer();
        assertThat(ringBuffer.consumerSequence()).isEqualTo(1L);

        ringBuffer.skipUnpublishedSlot();
        assertThat(ringBuffer.consumerSequence()).isEqualTo(2L);
        assertThat(ringBuffer.hasPending()).isFalse();

        assertThat(ringBuffer.offerOverflow(null)).isFalse();
        RawLogRecord overflowRecord = new RawLogRecord(
                "content",
                null,
                "INFO",
                "logger",
                "thread",
                1L,
                Collections.<String, String>emptyMap(),
                null);
        assertThat(ringBuffer.offerOverflow(overflowRecord)).isTrue();
        assertThat(ringBuffer.hasOverflow()).isTrue();
        assertThat(ringBuffer.overflowSize()).isEqualTo(1);
        assertThat(ringBuffer.overflowCount()).isEqualTo(1L);
        assertThat(ringBuffer.pollOverflow()).isSameAs(overflowRecord);
        assertThat(ringBuffer.hasOverflow()).isFalse();
    }

    @Test
    void pipelineRingBuffer_availableCountShouldBreakWhenFirstSlotUnpublished() {
        PipelineRingBuffer ringBuffer = new PipelineRingBuffer(4, 1);
        long first = ringBuffer.tryClaim();
        long second = ringBuffer.tryClaim();
        assertThat(first).isZero();
        assertThat(second).isEqualTo(1L);

        ringBuffer.publish(second);
        assertThat(ringBuffer.availableCount(4)).isZero();
    }
}
