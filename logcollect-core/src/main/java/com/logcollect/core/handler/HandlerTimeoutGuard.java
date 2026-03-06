package com.logcollect.core.handler;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Lock-free timeout guard based on deadline registration + watchdog scanning.
 */
public final class HandlerTimeoutGuard {

    public interface TimeoutListener {
        void onTimeout(TimeoutEvent event);
    }

    public static final class TimeoutEvent {
        private final String methodKey;
        private final String phase;
        private final long timeoutMs;
        private final Thread targetThread;

        TimeoutEvent(String methodKey, String phase, long timeoutMs, Thread targetThread) {
            this.methodKey = methodKey;
            this.phase = phase;
            this.timeoutMs = timeoutMs;
            this.targetThread = targetThread;
        }

        public String getMethodKey() {
            return methodKey;
        }

        public String getPhase() {
            return phase;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public Thread getTargetThread() {
            return targetThread;
        }
    }

    public static final class DeregisterResult {
        private static final DeregisterResult OVERWRITTEN = new DeregisterResult(false, false);

        private final boolean owner;
        private final boolean timedOut;

        private DeregisterResult(boolean owner, boolean timedOut) {
            this.owner = owner;
            this.timedOut = timedOut;
        }

        public boolean isOwner() {
            return owner;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        static DeregisterResult of(boolean timedOut) {
            return new DeregisterResult(true, timedOut);
        }
    }

    private static final class HandlerInvocation {
        volatile boolean active;
        volatile boolean timedOut;
        volatile long token;
        volatile long deadlineNanos;
        volatile long timeoutNanos;
        volatile Thread thread;
        volatile String methodKey;
        volatile String phase;

        void clear() {
            active = false;
            timedOut = false;
            deadlineNanos = 0L;
            timeoutNanos = 0L;
            thread = null;
            methodKey = null;
            phase = null;
            token = 0L;
        }
    }

    private final HandlerInvocation[] activeSlots;
    private final int slotMask;
    private final int watchdogIntervalMs;
    private final TimeoutListener timeoutListener;
    private final AtomicInteger slotCursor = new AtomicInteger(0);
    private final AtomicLong tokenSeq = new AtomicLong(1L);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final Object watchdogInitLock = new Object();
    private volatile Thread watchdogThread;

    public HandlerTimeoutGuard(int requestedSlots, int watchdogIntervalMs, TimeoutListener timeoutListener) {
        int normalizedSlots = normalizeSlots(requestedSlots);
        this.activeSlots = new HandlerInvocation[normalizedSlots];
        for (int i = 0; i < normalizedSlots; i++) {
            activeSlots[i] = new HandlerInvocation();
        }
        this.slotMask = normalizedSlots - 1;
        this.watchdogIntervalMs = Math.max(10, watchdogIntervalMs);
        this.timeoutListener = timeoutListener;
    }

    public int slotCount() {
        return activeSlots.length;
    }

    public int watchdogIntervalMs() {
        return watchdogIntervalMs;
    }

    public boolean matches(int requestedSlots, int requestedIntervalMs) {
        return activeSlots.length == normalizeSlots(requestedSlots)
                && watchdogIntervalMs == Math.max(10, requestedIntervalMs);
    }

    /**
     * @return opaque invocation handle
     */
    public long registerDeadline(Thread callerThread,
                                 long deadlineNanos,
                                 long timeoutNanos,
                                 String methodKey,
                                 String phase) {
        int slot = claimSlot();
        long token = tokenSeq.getAndIncrement();
        HandlerInvocation inv = activeSlots[slot];
        inv.token = token;
        inv.thread = callerThread;
        inv.deadlineNanos = deadlineNanos;
        inv.timeoutNanos = timeoutNanos;
        inv.methodKey = methodKey;
        inv.phase = phase;
        inv.timedOut = false;
        inv.active = true;
        ensureWatchdogStarted();
        return composeHandle(slot, token);
    }

    public boolean wasTimedOut(long handle) {
        int slot = extractSlot(handle);
        long token = extractToken(handle);
        HandlerInvocation inv = activeSlots[slot];
        return inv.token == token && inv.active && inv.timedOut;
    }

    public DeregisterResult deregister(long handle) {
        int slot = extractSlot(handle);
        long token = extractToken(handle);
        HandlerInvocation inv = activeSlots[slot];
        if (inv.token != token) {
            return DeregisterResult.OVERWRITTEN;
        }
        boolean timedOut = inv.timedOut;
        inv.clear();
        return DeregisterResult.of(timedOut);
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread watchdog = watchdogThread;
        if (watchdog != null) {
            watchdog.interrupt();
        }
    }

    private int claimSlot() {
        return slotCursor.getAndIncrement() & slotMask;
    }

    private void ensureWatchdogStarted() {
        if (watchdogThread != null) {
            return;
        }
        synchronized (watchdogInitLock) {
            if (watchdogThread != null) {
                return;
            }
            Thread worker = new Thread(this::watchdogLoop, "logcollect-handler-watchdog");
            worker.setDaemon(true);
            worker.start();
            watchdogThread = worker;
        }
    }

    private void watchdogLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(watchdogIntervalMs));
                scanAndInterruptTimedOutInvocations();
            } catch (Throwable ignored) {
                // Keep watchdog alive; timeout guarding is best-effort.
            }
        }
    }

    private void scanAndInterruptTimedOutInvocations() {
        long now = System.nanoTime();
        for (HandlerInvocation inv : activeSlots) {
            if (!inv.active || inv.timedOut) {
                continue;
            }
            if (now <= inv.deadlineNanos) {
                continue;
            }
            inv.timedOut = true;
            Thread target = inv.thread;
            if (target != null) {
                target.interrupt();
            }
            if (timeoutListener != null) {
                timeoutListener.onTimeout(new TimeoutEvent(
                        inv.methodKey,
                        inv.phase,
                        TimeUnit.NANOSECONDS.toMillis(inv.timeoutNanos),
                        target));
            }
        }
    }

    private static int normalizeSlots(int requestedSlots) {
        int value = Math.max(8, requestedSlots);
        int normalized = Integer.highestOneBit(value - 1) << 1;
        return normalized <= 0 ? 8 : normalized;
    }

    private static long composeHandle(int slot, long token) {
        return (token << 32) | (slot & 0xFFFFFFFFL);
    }

    private static int extractSlot(long handle) {
        return (int) handle;
    }

    private static long extractToken(long handle) {
        return handle >>> 32;
    }
}
