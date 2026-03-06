package com.logcollect.core.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineQueueTest {

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
}
