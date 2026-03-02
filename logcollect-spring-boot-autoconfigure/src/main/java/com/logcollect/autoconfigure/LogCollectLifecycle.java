package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.core.buffer.AsyncFlushExecutor;
import com.logcollect.core.buffer.LogCollectBuffer;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class LogCollectLifecycle implements SmartLifecycle, DisposableBean, InitializingBean {

    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 15_000L;

    private final LogCollectBufferRegistry registry;
    private final LogCollectGlobalSwitch globalSwitch;
    private final LogCollectMetrics metrics;
    private final long shutdownTimeoutMs;
    private volatile boolean running = false;

    public LogCollectLifecycle(LogCollectBufferRegistry registry) {
        this(registry, null, null, DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    public LogCollectLifecycle(LogCollectBufferRegistry registry,
                               LogCollectGlobalSwitch globalSwitch,
                               LogCollectMetrics metrics,
                               long shutdownTimeoutMs) {
        this.registry = registry;
        this.globalSwitch = globalSwitch;
        this.metrics = metrics;
        this.shutdownTimeoutMs = shutdownTimeoutMs <= 0 ? DEFAULT_SHUTDOWN_TIMEOUT_MS : shutdownTimeoutMs;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        stop(() -> {
            // no-op
        });
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        if (globalSwitch != null) {
            globalSwitch.setEnabled(false);
        }
        long deadline = System.currentTimeMillis() + shutdownTimeoutMs;
        waitActiveCollections(deadline);
        drainAll(Math.max(0L, deadline - System.currentTimeMillis()));
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        drainAll(shutdownTimeoutMs);
    }

    @Override
    public void afterPropertiesSet() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> drainAll(shutdownTimeoutMs), "logcollect-shutdown"));
    }

    private void waitActiveCollections(long deadline) {
        if (metrics == null) {
            return;
        }
        while (metrics.getActiveCollections() > 0 && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
    }

    private void drainAll(long timeoutMs) {
        if (registry == null) {
            return;
        }
        for (LogCollectBuffer buffer : registry.all()) {
            try {
                buffer.forceFlush();
            } catch (Exception ex) {
                emergencyDump(buffer);
            } catch (Error e) {
                throw e;
            }
        }
        AsyncFlushExecutor.shutdownAndAwait(timeoutMs);
    }

    private void emergencyDump(LogCollectBuffer buffer) {
        try {
            Path path = Paths.get(System.getProperty("java.io.tmpdir"),
                    "logcollect-emergency-" + System.currentTimeMillis() + ".log");
            Files.write(path,
                    buffer.dumpAsString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
            System.err.println("[LogCollect] Emergency dump: " + path);
        } catch (IOException ex) {
            System.err.println("[LogCollect] CRITICAL: emergency dump failed: " + ex);
        }
    }
}
