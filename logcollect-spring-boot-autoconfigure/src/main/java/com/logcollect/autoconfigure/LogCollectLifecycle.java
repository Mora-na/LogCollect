package com.logcollect.autoconfigure;

import com.logcollect.core.buffer.LogCollectBuffer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class LogCollectLifecycle implements SmartLifecycle, DisposableBean, InitializingBean {

    private final LogCollectBufferRegistry registry;
    private volatile boolean running = false;

    public LogCollectLifecycle(LogCollectBufferRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        drainAll();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        drainAll();
    }

    @Override
    public void afterPropertiesSet() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::drainAll, "logcollect-shutdown"));
    }

    private void drainAll() {
        if (registry == null) {
            return;
        }
        for (LogCollectBuffer buffer : registry.all()) {
            try {
                buffer.forceFlush();
            } catch (Exception ex) {
                emergencyDump(buffer);
            }
        }
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
