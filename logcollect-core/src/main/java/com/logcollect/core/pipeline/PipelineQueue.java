package com.logcollect.core.pipeline;

/**
 * @deprecated Deprecated since v2.2 and will be removed in v2.3.
 * Use {@link PipelineRingBuffer} instead.
 */
@Deprecated(since = "2.2", forRemoval = true)
public final class PipelineQueue {

    public enum OfferResult {
        ACCEPTED,
        BACKPRESSURE_REJECTED,
        FULL
    }

    /**
     * @deprecated Deprecated since v2.2 and will be removed in v2.3.
     */
    @Deprecated
    public PipelineQueue(int capacity) {
        throw new UnsupportedOperationException(
                "PipelineQueue is deprecated since V2.2. Use PipelineRingBuffer instead. "
                        + "This class will be removed in V2.3.");
    }

    /**
     * @deprecated Deprecated since v2.2 and will be removed in v2.3.
     */
    @Deprecated
    public PipelineQueue(int capacity, double warningRatio, double criticalRatio) {
        this(capacity);
    }

    /**
     * @deprecated Deprecated since v2.2 and will be removed in v2.3.
     */
    @Deprecated
    public OfferResult offer(RawLogRecord record) {
        throw unsupported();
    }

    /**
     * @deprecated Deprecated since v2.2 and will be removed in v2.3.
     */
    @Deprecated
    public boolean forceOffer(RawLogRecord record) {
        throw unsupported();
    }

    /**
     * @deprecated Deprecated since v2.2 and will be removed in v2.3.
     */
    @Deprecated
    public RawLogRecord poll() {
        throw unsupported();
    }

    /**
     * @deprecated Deprecated since v2.2 and will be removed in v2.3.
     */
    @Deprecated
    public int size() {
        throw unsupported();
    }

    /**
     * @deprecated Deprecated since v2.2 and will be removed in v2.3.
     */
    @Deprecated
    public int capacity() {
        throw unsupported();
    }

    /**
     * @deprecated Deprecated since v2.2 and will be removed in v2.3.
     */
    @Deprecated
    public double utilization() {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "PipelineQueue is deprecated since V2.2. Use PipelineRingBuffer instead.");
    }
}
