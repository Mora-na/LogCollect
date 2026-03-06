package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogCollectContext;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Ready-queue for event-driven consumer wakeup.
 */
public final class ConsumerReadyQueue {

    private final ConcurrentLinkedQueue<LogCollectContext> queue = new ConcurrentLinkedQueue<LogCollectContext>();

    public void signal(LogCollectContext context) {
        if (context == null || context.isClosed()) {
            return;
        }
        if (context.markPipelineReady()) {
            queue.offer(context);
        }
    }

    public LogCollectContext poll() {
        return queue.poll();
    }
}
