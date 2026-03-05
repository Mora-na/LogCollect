package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.security.SecurityComponentRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V2 双阶段流水线管理器。
 */
public final class LogCollectPipelineManager {

    private final List<PipelineConsumer> consumers;
    private final List<Thread> workers;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public LogCollectPipelineManager(int consumerThreads, SecurityComponentRegistry securityRegistry) {
        int normalizedThreads = Math.max(1, consumerThreads);
        this.consumers = new ArrayList<PipelineConsumer>(normalizedThreads);
        this.workers = new ArrayList<Thread>(normalizedThreads);
        for (int i = 0; i < normalizedThreads; i++) {
            PipelineConsumer consumer = new PipelineConsumer("pipeline-consumer-" + i, securityRegistry);
            Thread worker = new Thread(consumer, "logcollect-" + consumer.consumerName());
            worker.setDaemon(true);
            consumers.add(consumer);
            workers.add(worker);
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (Thread worker : workers) {
            worker.start();
        }
    }

    public void shutdown(long timeoutMs) {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        for (PipelineConsumer consumer : consumers) {
            consumer.shutdown();
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(100L, timeoutMs));
        for (Thread worker : workers) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            try {
                worker.join(TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void registerContext(LogCollectContext context) {
        if (context == null) {
            return;
        }
        LogCollectConfig config = context.getConfig();
        int ringCapacity = config == null ? 4096 : Math.max(2, config.getPipelineRingBufferCapacity());
        int overflowCapacity = config == null ? 1024 : Math.max(1, config.getPipelineOverflowQueueCapacity());
        context.setPipelineQueue(new PipelineRingBuffer(ringCapacity, overflowCapacity));

        PipelineConsumer consumer = selectConsumer(context);
        if (consumer != null) {
            consumer.assign(context);
        }
    }

    public void closeContext(LogCollectContext context) {
        if (context == null) {
            return;
        }
        PipelineConsumer consumer = null;
        Object bound = context.getPipelineConsumer();
        if (bound instanceof PipelineConsumer) {
            consumer = (PipelineConsumer) bound;
        }
        if (consumer == null) {
            consumer = selectConsumer(context);
        }
        if (consumer == null) {
            context.markClosed();
            return;
        }
        long handoffTimeoutMs = 5L;
        LogCollectConfig config = context.getConfig();
        if (config != null) {
            handoffTimeoutMs = Math.max(1L, config.getPipelineHandoffTimeoutMs())
                    + Math.max(1L, config.getPipelineUnpublishedSlotTimeoutMs());
        }
        consumer.closeAndFlush(context, TimeUnit.MILLISECONDS.toNanos(handoffTimeoutMs));
    }

    private PipelineConsumer selectConsumer(LogCollectContext context) {
        if (consumers.isEmpty() || context == null) {
            return null;
        }
        int hash = context.getTraceId() == null ? context.hashCode() : context.getTraceId().hashCode();
        int idx = (hash & Integer.MAX_VALUE) % consumers.size();
        return consumers.get(idx);
    }
}
