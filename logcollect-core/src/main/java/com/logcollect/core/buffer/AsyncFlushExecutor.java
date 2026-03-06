package com.logcollect.core.buffer;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Lock-free async flush executor based on MPSC queue + worker threads.
 */
public final class AsyncFlushExecutor {

    public interface RejectedAwareTask extends Runnable {
        default Runnable downgradeForRetry() {
            return null;
        }

        default void onDiscard(String reason) {
        }
    }

    private static final long IDLE_PARK_NANOS = 100_000L;
    private static final AtomicLong REJECTED_COUNT = new AtomicLong(0L);
    private static final AtomicReference<LockFreeExecutor> EXECUTOR_REF =
            new AtomicReference<LockFreeExecutor>(new LockFreeExecutor(defaultMaxThreads(), 4096));

    private AsyncFlushExecutor() {
    }

    public static void submitOrRun(Runnable task) {
        if (task == null) {
            return;
        }

        LockFreeExecutor executor = EXECUTOR_REF.get();
        if (executor == null || executor.isShutdown()) {
            runSafely(task, "executor_shutdown");
            return;
        }

        if (executor.offer(task)) {
            return;
        }

        REJECTED_COUNT.incrementAndGet();
        if (task instanceof RejectedAwareTask) {
            RejectedAwareTask rejectedAwareTask = (RejectedAwareTask) task;
            Runnable downgraded = rejectedAwareTask.downgradeForRetry();
            if (downgraded != null && executor.offer(downgraded)) {
                return;
            }
            try {
                rejectedAwareTask.onDiscard("executor_queue_full");
            } catch (Exception ignored) {
                // fallback to caller-runs below
            }
        }

        runSafely(task, "executor_queue_full_caller_runs");
    }

    public static long getRejectedCount() {
        return REJECTED_COUNT.get();
    }

    public static void configure(int coreThreads, int maxThreads, int queueCapacity) {
        int normalizedCore = Math.max(1, coreThreads);
        int normalizedMax = Math.max(normalizedCore, maxThreads);
        int normalizedQueue = Math.max(1, queueCapacity);

        LockFreeExecutor replacement = new LockFreeExecutor(normalizedMax, normalizedQueue);
        LockFreeExecutor old = EXECUTOR_REF.getAndSet(replacement);
        if (old != null) {
            old.shutdown(0L, false);
        }
    }

    public static void resize(int coreThreads, int maxThreads) {
        int normalizedCore = Math.max(1, coreThreads);
        int normalizedMax = Math.max(normalizedCore, maxThreads);
        LockFreeExecutor current = EXECUTOR_REF.get();
        if (current != null) {
            current.resizeWorkers(normalizedMax);
        }
    }

    public static void shutdownAndAwait(long timeoutMs) {
        LockFreeExecutor current = EXECUTOR_REF.get();
        if (current == null) {
            return;
        }
        current.shutdown(Math.max(0L, timeoutMs), true);
    }

    private static int defaultCoreThreads() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
    }

    private static int defaultMaxThreads() {
        return Math.max(4, Runtime.getRuntime().availableProcessors());
    }

    private static void runSafely(Runnable task, String reason) {
        try {
            task.run();
        } catch (Exception ex) {
            LogCollectInternalLogger.warn("Run async flush task failed, reason={}", reason, ex);
        } catch (Error e) {
            throw e;
        }
    }

    private static final class LockFreeExecutor {
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
        private final AtomicInteger queueSize = new AtomicInteger(0);
        private final AtomicInteger workerSeq = new AtomicInteger(0);
        private final AtomicInteger desiredWorkers = new AtomicInteger(0);
        private final List<Worker> workers = new CopyOnWriteArrayList<Worker>();
        private final AtomicBoolean shutdown = new AtomicBoolean(false);

        private volatile int maxQueueSize;

        private LockFreeExecutor(int workerCount, int maxQueueSize) {
            this.maxQueueSize = Math.max(1, maxQueueSize);
            int normalizedWorkers = Math.max(1, workerCount);
            this.desiredWorkers.set(normalizedWorkers);
            ensureWorkers(normalizedWorkers);
        }

        private boolean offer(Runnable task) {
            if (task == null || shutdown.get()) {
                return false;
            }
            int currentSize = queueSize.get();
            if (currentSize >= maxQueueSize) {
                return false;
            }
            queue.offer(task);
            queueSize.incrementAndGet();
            unparkOne();
            return true;
        }

        private boolean isShutdown() {
            return shutdown.get();
        }

        private void resizeWorkers(int workerCount) {
            if (shutdown.get()) {
                return;
            }
            int normalizedWorkers = Math.max(1, workerCount);
            desiredWorkers.set(normalizedWorkers);
            ensureWorkers(normalizedWorkers);
            unparkAll();
        }

        private void shutdown(long timeoutMs, boolean interruptWorkers) {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            desiredWorkers.set(0);

            if (interruptWorkers) {
                for (Worker worker : workers) {
                    Thread thread = worker.thread;
                    if (thread != null) {
                        thread.interrupt();
                    }
                }
            } else {
                unparkAll();
            }

            long deadlineNanos = timeoutMs <= 0
                    ? 0L
                    : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            for (Worker worker : workers) {
                Thread thread = worker.thread;
                if (thread == null) {
                    continue;
                }
                long joinMillis = timeoutMs <= 0
                        ? 0L
                        : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
                try {
                    if (timeoutMs <= 0) {
                        thread.join(1L);
                    } else {
                        if (System.nanoTime() >= deadlineNanos) {
                            break;
                        }
                        thread.join(joinMillis);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Runnable remaining;
            while ((remaining = queue.poll()) != null) {
                decrementQueueSize();
                runSafely(remaining, "executor_shutdown_drain");
            }
        }

        private void ensureWorkers(int expectedCount) {
            while (workers.size() < expectedCount) {
                int index = workerSeq.getAndIncrement();
                Worker worker = new Worker(index);
                workers.add(worker);
                Thread thread = new Thread(() -> runWorker(worker), "logcollect-flush-" + (index + 1));
                thread.setDaemon(true);
                worker.thread = thread;
                thread.start();
            }
        }

        private void runWorker(Worker worker) {
            while (true) {
                if (shutdown.get()) {
                    if (queue.isEmpty()) {
                        return;
                    }
                } else if (worker.index >= desiredWorkers.get() && queue.isEmpty()) {
                    return;
                }

                Runnable task = queue.poll();
                if (task != null) {
                    decrementQueueSize();
                    runSafely(task, "executor_worker");
                    continue;
                }

                LockSupport.parkNanos(IDLE_PARK_NANOS);
                if (Thread.interrupted() && shutdown.get()) {
                    // continue loop and honor shutdown branch at top
                }
            }
        }

        private void decrementQueueSize() {
            int size = queueSize.decrementAndGet();
            if (size < 0) {
                queueSize.compareAndSet(size, 0);
            }
        }

        private void unparkOne() {
            List<Worker> snapshot = workers;
            int size = snapshot.size();
            if (size == 0) {
                return;
            }
            Worker worker = snapshot.get(ThreadLocalRandom.current().nextInt(size));
            Thread thread = worker.thread;
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }

        private void unparkAll() {
            for (Worker worker : workers) {
                Thread thread = worker.thread;
                if (thread != null) {
                    LockSupport.unpark(thread);
                }
            }
        }
    }

    private static final class Worker {
        private final int index;
        private volatile Thread thread;

        private Worker(int index) {
            this.index = index;
        }
    }
}
