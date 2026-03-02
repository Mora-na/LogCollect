package com.logcollect.core.degrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DegradeFileManagerAdditionalTest {

    @TempDir
    Path tempDir;

    @Test
    void write_beforeInitialize_throwsIllegalState() {
        DegradeFileManager manager = new DegradeFileManager(tempDir, 1024 * 1024, 7, null);
        assertThatThrownBy(() -> manager.write(UUID.randomUUID().toString(), "m", "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void initialize_withNullBaseDir_safe() {
        DegradeFileManager manager = new DegradeFileManager(null, 1024 * 1024, 7, null);
        manager.initialize();
        assertThat(manager.isInitialized()).isFalse();
        assertThat(manager.getDiskFreeSpace()).isEqualTo(-1L);
    }

    @Test
    void writeUsingLineList_and_cleanAllFiles() throws Exception {
        DegradeFileManager manager = new DegradeFileManager(tempDir, 1024 * 1024, 7, null);
        manager.initialize();

        manager.write(UUID.randomUUID().toString(), Arrays.asList("line-1", "line-2"));
        assertThat(manager.getFileCount()).isEqualTo(1);
        assertThat(manager.getTotalSizeBytes()).isGreaterThan(0L);

        DegradeFileManager.CleanupResult result = manager.cleanAllFiles();
        assertThat(result.getDeletedCount()).isGreaterThanOrEqualTo(1);
        assertThat(manager.getFileCount()).isEqualTo(0);
        assertThat(manager.getTotalSizeBytes()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void getters_and_humanReadableValues_available() throws Exception {
        DegradeFileManager manager = new DegradeFileManager(tempDir, 1024 * 1024, 7, null);
        manager.initialize();
        String trace = UUID.randomUUID().toString();
        manager.write(trace, "m", "content");

        assertThat(manager.getBaseDir()).isEqualTo(tempDir);
        assertThat(manager.getMaxTotalBytes()).isEqualTo(1024 * 1024);
        assertThat(manager.getTtlDays()).isEqualTo(7);
        assertThat(manager.getTotalSizeHuman()).isNotBlank();
        assertThat(manager.getDiskFreeSpace()).isGreaterThan(0L);
        assertThat(manager.getDiskFreeSpaceHuman()).isNotBlank();

        Path file = Files.list(tempDir).findFirst().orElseThrow(AssertionError::new);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("content");
    }

    @Test
    void cleanExpiredFiles_whenBaseDirMissing_returnsZero() {
        Path missing = tempDir.resolve("missing-dir");
        DegradeFileManager manager = new DegradeFileManager(missing, 1024 * 1024, 7, null);
        DegradeFileManager.CleanupResult result = manager.cleanExpiredFiles();
        assertThat(result.getDeletedCount()).isZero();
        assertThat(result.getDeletedBytes()).isZero();
    }
}
