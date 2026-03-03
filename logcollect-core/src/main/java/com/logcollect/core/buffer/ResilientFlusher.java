package com.logcollect.core.buffer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class ResilientFlusher {

    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 100L;
    private static final long MAX_DELAY_MS = 2000L;
    private static final File FALLBACK_DIR = new File(
            System.getProperty("java.io.tmpdir"), "logcollect-fallback");
    private static final ScheduledExecutorService RETRY_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "logcollect-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    public boolean flush(Runnable action, Supplier<String> dataSnapshot) {
        return flushBlocking(action, dataSnapshot);
    }

    public void flushBatch(Runnable action,
                           Runnable onSuccess,
                           Runnable onExhausted,
                           Supplier<String> dataSnapshot,
                           boolean runSynchronously) {
        if (runSynchronously) {
            boolean success = flushBlocking(action, dataSnapshot);
            if (success) {
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } else if (onExhausted != null) {
                onExhausted.run();
            }
            return;
        }
        flushBatchAsync(action, onSuccess, onExhausted, dataSnapshot, 0);
    }

    private boolean flushBlocking(Runnable action, Supplier<String> dataSnapshot) {
        Exception lastEx = null;
        for (int i = 0; i <= MAX_RETRIES; i++) {
            try {
                action.run();
                return true;
            } catch (Exception ex) {
                lastEx = ex;
                if (i >= MAX_RETRIES) {
                    break;
                }
                long delay = backoffWithJitter(i);
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
        if (delayMs <= 0) {
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        java.util.concurrent.ScheduledFuture<?> delayFuture =
                RETRY_SCHEDULER.schedule(latch::countDown, delayMs, TimeUnit.MILLISECONDS);
        try {
            latch.await();
            return true;
        } catch (InterruptedException interruptedException) {
            delayFuture.cancel(false);
            Thread.currentThread().interrupt();
            return false;
        }
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
            long delay = backoffWithJitter(attempt);
            RETRY_SCHEDULER.schedule(
                    () -> flushBatchAsync(action, onSuccess, onExhausted, dataSnapshot, attempt + 1),
                    delay,
                    TimeUnit.MILLISECONDS);
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
}
