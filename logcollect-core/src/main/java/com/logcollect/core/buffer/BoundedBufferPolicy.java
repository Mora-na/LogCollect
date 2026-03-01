package com.logcollect.core.buffer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BoundedBufferPolicy {

    public enum OverflowStrategy {
        FLUSH_EARLY,
        DROP_OLDEST,
        DROP_NEWEST
    }

    public enum RejectReason {
        ACCEPTED,
        BUFFER_FULL,
        GLOBAL_MEMORY_LIMIT
    }

    private final long maxBytes;
    private final int maxEntries;
    private final OverflowStrategy strategy;

    private final AtomicLong currentBytes = new AtomicLong(0);
    private final AtomicInteger currentCount = new AtomicInteger(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    public BoundedBufferPolicy(long maxBytes, int maxEntries, OverflowStrategy strategy) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
        this.strategy = strategy == null ? OverflowStrategy.FLUSH_EARLY : strategy;
    }

    public RejectReason beforeAdd(long entryBytes, Runnable earlyFlush) {
        if (isOverflow(entryBytes)) {
            switch (strategy) {
                case FLUSH_EARLY:
                    if (earlyFlush != null) {
                        earlyFlush.run();
                    }
                    break;
                case DROP_NEWEST:
                    droppedCount.incrementAndGet();
                    return RejectReason.BUFFER_FULL;
                case DROP_OLDEST:
                    break;
                default:
                    break;
            }
        }
        currentBytes.addAndGet(entryBytes);
        currentCount.incrementAndGet();
        return RejectReason.ACCEPTED;
    }

    public void afterDrain(long bytesRemoved, int countRemoved) {
        if (bytesRemoved > 0) {
            long left = currentBytes.addAndGet(-bytesRemoved);
            if (left < 0) {
                currentBytes.set(0);
            }
        }
        if (countRemoved > 0) {
            long left = currentCount.addAndGet(-countRemoved);
            if (left < 0) {
                currentCount.set(0);
            }
        }
    }

    public void recordDropped() {
        droppedCount.incrementAndGet();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getCurrentBytes() {
        return currentBytes.get();
    }

    public int getCurrentCount() {
        return currentCount.get();
    }

    public OverflowStrategy getStrategy() {
        return strategy;
    }

    public boolean isOverflow(long entryBytes) {
        boolean bytesOverflow = maxBytes > 0 && currentBytes.get() + entryBytes > maxBytes;
        boolean entriesOverflow = maxEntries > 0 && currentCount.get() >= maxEntries;
        return bytesOverflow || entriesOverflow;
    }
}
