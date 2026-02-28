package com.logcollect.api.model;

import com.logcollect.api.enums.DegradeStorage;

import java.time.LocalDateTime;

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
}
