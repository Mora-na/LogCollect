package com.logcollect.core.pipeline;

import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineQueueTest {

    @Test
    void offerResult_enumValues_shouldExposeLegacyOutcomes() {
        assertThat(PipelineQueue.OfferResult.values()).containsExactly(
                PipelineQueue.OfferResult.ACCEPTED,
                PipelineQueue.OfferResult.BACKPRESSURE_REJECTED,
                PipelineQueue.OfferResult.FULL);
    }

    @Test
    void constructor_withCapacityOnly_shouldThrowUnsupportedOperation() {
        assertThatThrownBy(() -> new PipelineQueue(16))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PipelineQueue is deprecated since V2.2");
    }

    @Test
    void constructor_withLegacyThresholdArgs_shouldThrowUnsupportedOperation() {
        assertThatThrownBy(() -> new PipelineQueue(16, 0.7d, 0.9d))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PipelineQueue is deprecated since V2.2");
    }

    @Test
    void legacyInstanceMethods_shouldThrowUnsupportedOperation() throws Exception {
        PipelineQueue queue = allocateWithoutConstructor();

        assertThatThrownBy(() -> queue.offer(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Use PipelineRingBuffer instead");
        assertThatThrownBy(() -> queue.forceOffer(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Use PipelineRingBuffer instead");
        assertThatThrownBy(queue::poll)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Use PipelineRingBuffer instead");
        assertThatThrownBy(queue::size)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Use PipelineRingBuffer instead");
        assertThatThrownBy(queue::capacity)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Use PipelineRingBuffer instead");
        assertThatThrownBy(queue::utilization)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Use PipelineRingBuffer instead");
    }

    private PipelineQueue allocateWithoutConstructor() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (PipelineQueue) unsafe.allocateInstance(PipelineQueue.class);
    }
}
