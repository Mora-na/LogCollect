package com.logcollect.core.pipeline;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * MPSC 预分配环形缓冲区。
 */
public final class PipelineRingBuffer {

    private static final long UNPUBLISHED = Long.MIN_VALUE;

    private final MutableRawLogRecord[] slots;
    private final AtomicLongArray publishedSeqs;
    private final int capacity;
    private final int mask;
    private final int overflowCapacity;

    private final ProducerCursor producerCursor;
    private final ConsumerCursor consumerCursor;

    private final ConcurrentLinkedQueue<RawLogRecord> overflowQueue = new ConcurrentLinkedQueue<RawLogRecord>();
    private final AtomicInteger overflowSize = new AtomicInteger(0);
    private final LongAdder overflowCount = new LongAdder();

    public PipelineRingBuffer(int requestedCapacity, int overflowCapacity) {
        this.capacity = normalizeCapacity(requestedCapacity);
        this.mask = capacity - 1;
        this.overflowCapacity = Math.max(1, overflowCapacity);
        this.slots = new MutableRawLogRecord[capacity];
        this.publishedSeqs = new AtomicLongArray(capacity);
        this.producerCursor = new ProducerCursor();
        this.consumerCursor = new ConsumerCursor();

        for (int i = 0; i < capacity; i++) {
            slots[i] = new MutableRawLogRecord();
            publishedSeqs.set(i, UNPUBLISHED);
        }
    }

    public long tryClaim() {
        AtomicLong cursor = producerCursor.value;
        long current;
        do {
            current = cursor.get();
            if (current - consumerCursor.value >= capacity) {
                return -1L;
            }
        } while (!cursor.compareAndSet(current, current + 1));
        return current;
    }

    public MutableRawLogRecord getSlot(long sequence) {
        return slots[(int) (sequence & mask)];
    }

    public void publish(long sequence) {
        publishedSeqs.set((int) (sequence & mask), sequence);
    }

    public MutableRawLogRecord tryConsume() {
        long seq = consumerCursor.value;
        int index = (int) (seq & mask);
        if (publishedSeqs.get(index) != seq) {
            return null;
        }
        return slots[index];
    }

    public void advanceConsumer() {
        long seq = consumerCursor.value;
        markConsumed(seq);
        consumerCursor.value = seq + 1;
    }

    public void advanceConsumerBy(int count) {
        if (count <= 0) {
            return;
        }
        consumerCursor.value = consumerCursor.value + count;
    }

    public void markConsumed(long sequence) {
        int index = (int) (sequence & mask);
        slots[index].clearReferences();
        publishedSeqs.set(index, UNPUBLISHED);
    }

    public void skipUnpublishedSlot() {
        long seq = consumerCursor.value;
        markConsumed(seq);
        consumerCursor.value = seq + 1;
    }

    public boolean hasPending() {
        return consumerCursor.value < producerCursor.value.get();
    }

    public boolean hasAvailable() {
        return availableCount(1) > 0;
    }

    public boolean hasOverflow() {
        return overflowSize.get() > 0;
    }

    public boolean isPublished(long sequence) {
        return publishedSeqs.get((int) (sequence & mask)) == sequence;
    }

    public int availableCount() {
        return availableCount(64);
    }

    public int availableCount(int maxBatch) {
        int maxProbe = Math.max(1, maxBatch);
        long produced = producerCursor.value.get();
        long consumed = consumerCursor.value;
        int tentative = (int) Math.max(0L, produced - consumed);
        int limit = Math.min(tentative, maxProbe);
        int verified = 0;
        for (int i = 0; i < limit; i++) {
            long sequence = consumed + i;
            if (!isPublished(sequence)) {
                break;
            }
            verified++;
        }
        return verified;
    }

    public long pending() {
        long p = producerCursor.value.get();
        long c = consumerCursor.value;
        long v = p - c;
        return v < 0L ? 0L : v;
    }

    public double utilization() {
        return Math.min(1.0d, (double) pending() / (double) capacity);
    }

    public int capacity() {
        return capacity;
    }

    public long producerSequence() {
        return producerCursor.value.get();
    }

    public long consumerSequence() {
        return consumerCursor.value;
    }

    public boolean offerOverflow(RawLogRecord record) {
        if (record == null) {
            return false;
        }
        while (true) {
            int current = overflowSize.get();
            if (current >= overflowCapacity) {
                return false;
            }
            if (overflowSize.compareAndSet(current, current + 1)) {
                overflowQueue.offer(record);
                overflowCount.increment();
                return true;
            }
        }
    }

    public RawLogRecord pollOverflow() {
        RawLogRecord record = overflowQueue.poll();
        if (record != null) {
            overflowSize.decrementAndGet();
        }
        return record;
    }

    public int overflowSize() {
        return overflowSize.get();
    }

    public long overflowCount() {
        return overflowCount.sum();
    }

    private static int normalizeCapacity(int requestedCapacity) {
        int value = Math.max(2, requestedCapacity);
        int normalized = Integer.highestOneBit(value - 1) << 1;
        return normalized <= 0 ? 2 : normalized;
    }

    static class ProducerCursorPadding {
        long p1, p2, p3, p4, p5, p6, p7;
    }

    static class ProducerCursorValue extends ProducerCursorPadding {
        final AtomicLong value = new AtomicLong(0L);
    }

    static final class ProducerCursor extends ProducerCursorValue {
        long p8, p9, p10, p11, p12, p13, p14;
    }

    static class ConsumerCursorPadding {
        long c1, c2, c3, c4, c5, c6, c7;
    }

    static class ConsumerCursorValue extends ConsumerCursorPadding {
        volatile long value = 0L;
    }

    static final class ConsumerCursor extends ConsumerCursorValue {
        long c8, c9, c10, c11, c12, c13, c14;
    }
}
