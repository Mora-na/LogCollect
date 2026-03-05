package com.logcollect.core.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineQueueTest {

    @Test
    void warningThreshold_shouldDropDebugButKeepInfoAndWarn() {
        PipelineQueue queue = new PipelineQueue(100, 0.7d, 0.9d);
        for (int i = 0; i < 75; i++) {
            assertThat(queue.offer(raw("INFO"))).isEqualTo(PipelineQueue.OfferResult.ACCEPTED);
        }

        assertThat(queue.offer(raw("DEBUG"))).isEqualTo(PipelineQueue.OfferResult.BACKPRESSURE_REJECTED);
        assertThat(queue.offer(raw("INFO"))).isEqualTo(PipelineQueue.OfferResult.ACCEPTED);
        assertThat(queue.offer(raw("WARN"))).isEqualTo(PipelineQueue.OfferResult.ACCEPTED);
    }

    @Test
    void criticalThreshold_shouldKeepWarnAndErrorOnly() {
        PipelineQueue queue = new PipelineQueue(100, 0.7d, 0.9d);
        for (int i = 0; i < 92; i++) {
            queue.offer(raw("INFO"));
        }

        assertThat(queue.offer(raw("INFO"))).isEqualTo(PipelineQueue.OfferResult.BACKPRESSURE_REJECTED);
        assertThat(queue.offer(raw("WARN"))).isEqualTo(PipelineQueue.OfferResult.ACCEPTED);
        assertThat(queue.offer(raw("ERROR"))).isEqualTo(PipelineQueue.OfferResult.ACCEPTED);
    }

    @Test
    void fullQueue_shouldRejectAll() {
        PipelineQueue queue = new PipelineQueue(2, 0.7d, 0.9d);
        assertThat(queue.offer(raw("WARN"))).isEqualTo(PipelineQueue.OfferResult.ACCEPTED);
        assertThat(queue.offer(raw("WARN"))).isEqualTo(PipelineQueue.OfferResult.ACCEPTED);
        assertThat(queue.offer(raw("ERROR"))).isEqualTo(PipelineQueue.OfferResult.FULL);
    }

    @Test
    void forceOffer_shouldBypassBackpressureForHandoff() {
        PipelineQueue queue = new PipelineQueue(10, 0.5d, 0.6d);
        for (int i = 0; i < 6; i++) {
            assertThat(queue.offer(raw("INFO"))).isEqualTo(PipelineQueue.OfferResult.ACCEPTED);
        }

        assertThat(queue.offer(raw("INFO"))).isEqualTo(PipelineQueue.OfferResult.BACKPRESSURE_REJECTED);
        assertThat(queue.forceOffer(raw("INFO"))).isTrue();
        assertThat(queue.size()).isEqualTo(7);
    }

    private RawLogRecord raw(String level) {
        return new RawLogRecord("msg", null, level, "Logger", "main",
                System.currentTimeMillis(), Collections.emptyMap(), null);
    }
}
