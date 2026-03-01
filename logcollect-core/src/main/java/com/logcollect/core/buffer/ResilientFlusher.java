package com.logcollect.core.buffer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;

public class ResilientFlusher {

    private static final int MAX_RETRIES = 3;
    private static final File FALLBACK_DIR = new File(
            System.getProperty("java.io.tmpdir"), "logcollect-fallback");

    public boolean flush(Runnable action, Supplier<String> dataSnapshot) {
        Exception lastEx = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                action.run();
                return true;
            } catch (Exception ex) {
                lastEx = ex;
                sleepQuietly(100L * (1L << i));
            }
        }
        dumpToLocal(dataSnapshot == null ? "" : dataSnapshot.get(), lastEx);
        return false;
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

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
