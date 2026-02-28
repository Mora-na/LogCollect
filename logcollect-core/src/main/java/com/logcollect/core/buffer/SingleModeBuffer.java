package com.logcollect.core.buffer;

import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.DegradeEvent;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SingleModeBuffer implements LogCollectBuffer {
    private final ConcurrentLinkedQueue<LogEntry> queue = new ConcurrentLinkedQueue<LogEntry>();
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicLong bytes = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private final int maxCount;
    private final long maxBytes;
    private final GlobalBufferMemoryManager globalManager;

    public SingleModeBuffer(int maxCount, long maxBytes, GlobalBufferMemoryManager globalManager) {
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.globalManager = globalManager;
    }

    @Override
    public boolean offer(LogCollectContext context, LogEntry entry) {
        if (entry == null) {
            return false;
        }
        if (closed.get()) {
            if (context != null) {
                context.incrementDiscardedCount();
            }
            notifyDegrade(context, "buffer_closed");
            return false;
        }

        long entryBytes = entry.estimateBytes();
        if (maxBytes > 0 && entryBytes > maxBytes) {
            if (context != null) {
                context.incrementDiscardedCount();
            }
            notifyDegrade(context, "buffer_entry_too_large");
            return false;
        }

        if (globalManager != null && !globalManager.tryAllocate(entryBytes)) {
            if (isHighLevel(entry.getLevel())) {
                globalManager.forceAllocate(entryBytes);
            } else {
                if (context != null) {
                    context.incrementDiscardedCount();
                }
                notifyDegrade(context, "global_memory_limit");
                return false;
            }
        }

        if (maxBytes > 0 && bytes.get() + entryBytes > maxBytes && count.get() > 0) {
            triggerFlush(context, false);
        }

        queue.offer(entry);
        count.incrementAndGet();
        bytes.addAndGet(entryBytes);
        if (context != null) {
            context.incrementCollectedCount();
            context.addCollectedBytes(entryBytes);
        }

        if (shouldFlush()) {
            triggerFlush(context, false);
        }
        return true;
    }

    @Override
    public void triggerFlush(LogCollectContext context, boolean isFinal) {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<LogEntry> batch = drain();
            if (!batch.isEmpty()) {
                flushBatch(context, batch);
                if (context != null) {
                    context.incrementFlushCount();
                }
            }
        } finally {
            flushing.set(false);
        }
    }

    @Override
    public void closeAndFlush(LogCollectContext context) {
        closed.set(true);
        triggerFlush(context, true);
    }

    private boolean shouldFlush() {
        return (maxCount > 0 && count.get() >= maxCount) || (maxBytes > 0 && bytes.get() >= maxBytes);
    }

    private List<LogEntry> drain() {
        List<LogEntry> batch = new ArrayList<LogEntry>();
        LogEntry entry;
        long drainedBytes = 0;
        while ((entry = queue.poll()) != null) {
            batch.add(entry);
            drainedBytes += entry.estimateBytes();
        }
        if (!batch.isEmpty()) {
            int remaining = count.addAndGet(-batch.size());
            if (remaining < 0) {
                count.set(0);
            }
            long remainingBytes = bytes.addAndGet(-drainedBytes);
            if (remainingBytes < 0) {
                bytes.set(0);
            }
            if (globalManager != null) {
                globalManager.release(drainedBytes);
            }
        }
        return batch;
    }

    private void flushBatch(LogCollectContext context, List<LogEntry> batch) {
        LogCollectHandler handler = context == null ? null : context.getHandler();
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            if (context != null) {
                context.incrementDiscardedCount(batch.size());
            }
            notifyDegrade(context, "circuit_open");
            return;
        }
        for (LogEntry entry : batch) {
            try {
                if (handler != null) {
                    handler.appendLog(context, entry);
                }
                if (breaker != null) {
                    breaker.recordSuccess();
                }
            } catch (Throwable t) {
                if (breaker != null) {
                    breaker.recordFailure();
                }
                if (context != null) {
                    context.incrementDiscardedCount();
                }
                notifyError(context, t, "appendLog");
                notifyDegrade(context, "handler_error");
            }
        }
    }

    private LogCollectCircuitBreaker getBreaker(LogCollectContext context) {
        if (context == null) {
            return null;
        }
        Object breaker = context.getCircuitBreaker();
        return breaker instanceof LogCollectCircuitBreaker ? (LogCollectCircuitBreaker) breaker : null;
    }

    private boolean isHighLevel(String level) {
        if (level == null) {
            return false;
        }
        String v = level.toUpperCase();
        return "WARN".equals(v) || "ERROR".equals(v) || "FATAL".equals(v);
    }

    private void notifyDegrade(LogCollectContext context, String reason) {
        if (context == null) {
            return;
        }
        LogCollectHandler handler = context.getHandler();
        LogCollectConfig config = context.getConfig();
        if (handler == null || config == null) {
            return;
        }
        try {
            handler.onDegrade(context, new DegradeEvent(
                    context.getTraceId(),
                    context.getMethodSignature(),
                    reason,
                    config.getDegradeStorage(),
                    LocalDateTime.now()));
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("onDegrade callback failed", t);
        }
    }

    private void notifyError(LogCollectContext context, Throwable error, String phase) {
        if (context == null) {
            return;
        }
        LogCollectHandler handler = context.getHandler();
        if (handler == null) {
            return;
        }
        try {
            handler.onError(context, error, phase);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("onError callback failed", t);
        }
    }
}
