package com.logcollect.core.config;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class LogCollectLocalConfigCache {
    private final Path cacheFile;
    private final int maxAgeDays;

    public LogCollectLocalConfigCache(Path cacheFile, int maxAgeDays) {
        this.cacheFile = cacheFile;
        this.maxAgeDays = maxAgeDays;
    }

    public void save(Map<String, String> properties) {
        if (cacheFile == null || properties == null) {
            return;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Properties props = new Properties();
            props.putAll(properties);
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(out, "logcollect cache");
            }
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception t) {
            LogCollectInternalLogger.warn("Failed to save local config cache", t);
        } catch (Error e) {
            throw e;
        }
    }

    public Map<String, String> load() {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            return Collections.emptyMap();
        }
        try {
            FileTime lastModified = Files.getLastModifiedTime(cacheFile);
            Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);
            if (lastModified.toInstant().isBefore(cutoff)) {
                LogCollectInternalLogger.warn("Local config cache expired: {}", cacheFile);
                return Collections.emptyMap();
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(cacheFile)) {
                props.load(in);
            }
            Map<String, String> map = new HashMap<String, String>();
            for (String name : props.stringPropertyNames()) {
                map.put(name, props.getProperty(name));
            }
            return map;
        } catch (IOException e) {
            LogCollectInternalLogger.warn("Failed to load local config cache", e);
            return Collections.emptyMap();
        }
    }
}
