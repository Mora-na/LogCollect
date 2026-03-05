package com.logcollect.api.enums;

/**
 * 框架内部降级触发原因。
 *
 * <p>用于统一降级链路观测：Handler 失败、内存配额不足、缓冲区溢出等。
 */
public enum DegradeReason {
    PERSIST_FAILED("persist_failed"),
    CIRCUIT_OPEN("circuit_open"),
    OVERSIZED_FLUSH_FAILED("oversized_flush_failed"),
    OVERSIZED_GLOBAL_QUOTA_EXHAUSTED("oversized_global_quota_exhausted"),
    GLOBAL_QUOTA_EXHAUSTED("global_quota_exhausted"),
    GLOBAL_HARD_CEILING_REACHED("global_hard_ceiling_reached"),
    BUFFER_OVERFLOW_REJECTED("buffer_overflow_rejected"),
    BUFFER_OVERFLOW_EVICTED("buffer_overflow_evicted"),
    PIPELINE_QUEUE_FULL("pipeline_queue_full"),
    PIPELINE_BACKPRESSURE("pipeline_backpressure"),
    BUFFER_CLOSED_LATE_ARRIVAL("buffer_closed_late_arrival");

    private final String code;

    DegradeReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
