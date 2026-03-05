package com.logcollect.core.buffer;

/**
 * Consumer 单线程本地批量记账，降低全局 CAS 频率。
 */
public final class BatchedMemoryAccountant {

    private final GlobalBufferMemoryManager globalManager;
    private final long syncThreshold;

    private long localAllocated;
    private long localReleased;

    public BatchedMemoryAccountant(GlobalBufferMemoryManager globalManager, long syncThreshold) {
        this.globalManager = globalManager;
        this.syncThreshold = Math.max(1024L, syncThreshold);
    }

    public GlobalBufferMemoryManager globalManager() {
        return globalManager;
    }

    public boolean allocate(long bytes) {
        if (globalManager == null || bytes <= 0L) {
            return true;
        }
        long softLimit = globalManager.getMaxTotalBytes();
        if (softLimit > 0L && globalManager.getCurrentUsedBytes() >= softLimit) {
            return false;
        }
        localAllocated += bytes;
        if (localAllocated >= syncThreshold) {
            return syncAllocate();
        }
        return true;
    }

    public boolean forceAllocate(long bytes) {
        if (globalManager == null || bytes <= 0L) {
            return true;
        }
        syncRelease();
        return globalManager.forceAllocate(bytes);
    }

    public void release(long bytes) {
        if (globalManager == null || bytes <= 0L) {
            return;
        }
        localReleased += bytes;
        if (localReleased >= syncThreshold) {
            syncRelease();
        }
    }

    public void close() {
        syncAllocate();
        syncRelease();
    }

    private boolean syncAllocate() {
        if (globalManager == null || localAllocated <= 0L) {
            return true;
        }
        long toSync = localAllocated;
        localAllocated = 0L;
        if (globalManager.tryAllocate(toSync)) {
            return true;
        }
        localAllocated = toSync;
        return false;
    }

    private void syncRelease() {
        if (globalManager == null || localReleased <= 0L) {
            return;
        }
        long toSync = localReleased;
        localReleased = 0L;
        globalManager.release(toSync);
    }
}
