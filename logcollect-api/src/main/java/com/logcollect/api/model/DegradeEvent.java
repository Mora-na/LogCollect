package com.logcollect.api.model;

import com.logcollect.api.enums.DegradeReason;
import com.logcollect.api.enums.DegradeStorage;

import java.time.LocalDateTime;

/**
 * 降级事件模型。
 *
 * <p>当日志写入触发熔断/降级时，框架会通过
 * {@code LogCollectHandler#onDegrade(...)} 回调该对象。</p>
 *
 * @since 1.0.0
 */
public class DegradeEvent {
    private final String traceId;
    private final String methodSignature;
    private final String reason;
    private final DegradeStorage storage;
    private final LocalDateTime time;

    public DegradeEvent(String traceId, String methodSignature, String reason,
                        DegradeStorage storage, LocalDateTime time) {
        this.traceId = traceId;
        this.methodSignature = methodSignature;
        this.reason = reason;
        this.storage = storage;
        this.time = time;
    }

    public String getTraceId() { return traceId; }
    public String getMethodSignature() { return methodSignature; }
    public String getReason() { return reason; }
    public DegradeStorage getStorage() { return storage; }
    public LocalDateTime getTime() { return time; }

    public static DegradeEvent of(String traceId,
                                  String methodSignature,
                                  DegradeReason reason,
                                  DegradeStorage storage,
                                  LocalDateTime time) {
        return new DegradeEvent(traceId, methodSignature,
                reason == null ? "unknown" : reason.code(), storage, time);
    }
}
