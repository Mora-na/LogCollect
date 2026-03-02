package com.logcollect.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectLocalConfigCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoad_withInvalidPaths_coverExceptionBranches() throws Exception {
        LogCollectLocalConfigCache nullCache = new LogCollectLocalConfigCache(null, 7);
        nullCache.save(Collections.singletonMap("k", "v"));
        assertThat(nullCache.load()).isEmpty();

        Path noParent = Paths.get("logcollect-cache-no-parent.properties");
        LogCollectLocalConfigCache noParentCache = new LogCollectLocalConfigCache(noParent, 7);
        noParentCache.save(Collections.singletonMap("a", "b"));

        Path dirPath = tempDir.resolve("as-dir");
        Files.createDirectories(dirPath);
        LogCollectLocalConfigCache dirCache = new LogCollectLocalConfigCache(dirPath, 7);
        Map<String, String> loaded = dirCache.load();
        assertThat(loaded).isEmpty();
    }

    @Test
    void load_nonPositiveMaxAge_returnsEmpty() {
        Path cacheFile = tempDir.resolve("cache.properties");
        LogCollectLocalConfigCache zeroTtl = new LogCollectLocalConfigCache(cacheFile, 0);
        zeroTtl.save(Collections.singletonMap("a", "b"));
        assertThat(zeroTtl.load()).isEmpty();

        LogCollectLocalConfigCache negativeTtl = new LogCollectLocalConfigCache(cacheFile, -1);
        assertThat(negativeTtl.load()).isEmpty();
    }
}
