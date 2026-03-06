package com.logcollect.core.buffer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

public class ResilientFlusher {

    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 100L;
    private static final long MAX_DELAY_MS = 2000L;
    private static final long DEFAULT_SYNC_BACKOFF_CAP_MS = 200L;

    private static final File FALLBACK_DIR = new File(
            System.getProperty("java.io.tmpdir"), "logcollect-fallback");

    private static final ScheduledExecutorService RETRY_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "logcollect-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    private static final AtomicInteger PENDING_ASYNC_RETRIES = new AtomicInteger(0);

    public boolean flush(Runnable action, Supplier<String> dataSnapshot) {
        return flushBlocking(action, dataSnapshot, DEFAULT_SYNC_BACKOFF_CAP_MS);
    }

    public boolean flush(Runnable action, Supplier<String> dataSnapshot, long syncBackoffCapMs) {
        return flushBlocking(action, dataSnapshot, syncBackoffCapMs);
    }

    public void flushBatch(Runnable action,
                           Runnable onSuccess,
                           Runnable onExhausted,
                           Supplier<String> dataSnapshot,
                           boolean runSynchronously) {
        flushBatch(action, onSuccess, onExhausted, dataSnapshot, runSynchronously, DEFAULT_SYNC_BACKOFF_CAP_MS);
    }

    public void flushBatch(Runnable action,
                           Runnable onSuccess,
                           Runnable onExhausted,
                           Supplier<String> dataSnapshot,
                           boolean runSynchronously,
                           long syncBackoffCapMs) {
        if (runSynchronously) {
            long syncCap = normalizeSyncCap(syncBackoffCapMs);
            // Final/sync flush path: wait briefly for pending async retries to reduce interleaving.
            awaitPendingAsyncRetries(syncCap * MAX_RETRIES);
            boolean success = flushBlocking(action, dataSnapshot, syncCap);
            if (success) {
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } else if (onExhausted != null) {
                onExhausted.run();
            }
            return;
        }
        flushBatchAsync(action, onSuccess, onExhausted, dataSnapshot, 1);
    }

    public boolean awaitPendingAsyncRetries(long timeoutMs) {
        if (timeoutMs <= 0L) {
            return PENDING_ASYNC_RETRIES.get() == 0;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (PENDING_ASYNC_RETRIES.get() > 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        return true;
    }

    private boolean flushBlocking(Runnable action,
                                  Supplier<String> dataSnapshot,
                                  long syncBackoffCapMs) {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                action.run();
                return true;
            } catch (Exception ex) {
                lastEx = ex;
                if (attempt >= MAX_RETRIES) {
                    break;
                }
                long delay = Math.min(backoffWithJitter(attempt - 1), normalizeSyncCap(syncBackoffCapMs));
                if (!awaitBackoffDelay(delay)) {
                    break;
                }
            } catch (Error e) {
                throw e;
            }
        }
        dumpToLocal(dataSnapshot == null ? "" : dataSnapshot.get(), lastEx);
        return false;
    }

    private boolean awaitBackoffDelay(long delayMs) {
        if (delayMs <= 0L) {
            return true;
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs));
        if (Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    private void flushBatchAsync(Runnable action,
                                 Runnable onSuccess,
                                 Runnable onExhausted,
                                 Supplier<String> dataSnapshot,
                                 int attempt) {
        try {
            action.run();
            if (onSuccess != null) {
                onSuccess.run();
            }
            return;
        } catch (Exception ex) {
            if (attempt >= MAX_RETRIES) {
                dumpToLocal(dataSnapshot == null ? "" : dataSnapshot.get(), ex);
                if (onExhausted != null) {
                    onExhausted.run();
                }
                return;
            }

            long delay = backoffWithJitter(attempt - 1);
            PENDING_ASYNC_RETRIES.incrementAndGet();
            RETRY_SCHEDULER.schedule(() -> {
                try {
                    flushBatchAsync(action, onSuccess, onExhausted, dataSnapshot, attempt + 1);
                } finally {
                    PENDING_ASYNC_RETRIES.decrementAndGet();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Error e) {
            throw e;
        }
    }

    private void dumpToLocal(String data, Exception cause) {
        try {
            if (!FALLBACK_DIR.exists()) {
                FALLBACK_DIR.mkdirs();
            }
            Path path = new File(FALLBACK_DIR, "logcollect-" + System.currentTimeMillis() + ".log").toPath();
            Files.write(path, data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
            System.err.println("[LogCollect] Flush failed after retries, dumped to " + path
                    + ". Cause: " + (cause == null ? "unknown" : cause.getMessage()));
        } catch (IOException ex) {
            System.err.println("[LogCollect] CRITICAL: dump failed: " + ex.getMessage()
                    + ". Original cause: " + (cause == null ? "unknown" : cause.getMessage()));
        }
    }

    private long backoffWithJitter(int attempt) {
        long delay = Math.min(BASE_DELAY_MS * (1L << attempt), MAX_DELAY_MS);
        double jitter = 0.8d + ThreadLocalRandom.current().nextDouble() * 0.4d;
        return (long) (delay * jitter);
    }

    private long normalizeSyncCap(long syncBackoffCapMs) {
        if (syncBackoffCapMs <= 0L) {
            return DEFAULT_SYNC_BACKOFF_CAP_MS;
        }
        return syncBackoffCapMs;
    }
}
