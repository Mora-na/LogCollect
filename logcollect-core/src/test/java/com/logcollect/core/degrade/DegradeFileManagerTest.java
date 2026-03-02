package com.logcollect.core.degrade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DegradeFileManagerTest {

    @TempDir
    Path tempDir;

    private DegradeFileManager manager;

    @BeforeEach
    void setUp() {
        manager = new DegradeFileManager(tempDir, 10 * 1024 * 1024, 7, null);
        manager.initialize();
    }

    @Test
    void write_validTraceId_fileCreated() throws Exception {
        String traceId = UUID.randomUUID().toString();
        manager.write(traceId, "com.test.Service#run", "log content");

        long files = Files.list(tempDir).filter(Files::isRegularFile).count();
        assertThat(files).isEqualTo(1);
        Path created = Files.list(tempDir).filter(Files::isRegularFile).findFirst().get();
        assertThat(created.getFileName().toString()).startsWith(traceId);
        assertThat(new String(Files.readAllBytes(created), StandardCharsets.UTF_8)).contains("log content");
    }

    @Test
    void write_traceIdWithPathTraversal_rejected() throws Exception {
        assertDoesNotThrow(() -> manager.write("../../etc/passwd", "m", "evil"));
        assertThat(Files.exists(tempDir.resolve("../../etc/passwd.log"))).isFalse();
        assertThat(Files.list(tempDir).count()).isEqualTo(1);
    }

    @Test
    void write_traceIdWithSlash_rejected() throws Exception {
        assertDoesNotThrow(() -> manager.write("foo/bar", "m", "evil"));
        assertThat(Files.list(tempDir).count()).isEqualTo(1);
    }

    @Test
    void write_traceIdWithNull_handledGracefully() {
        assertDoesNotThrow(() -> manager.write(null, "m", "content"));
    }

    @Test
    void write_traceIdNonUUID_replacedWithSafe() throws Exception {
        manager.write("not-a-uuid!!!", "m", "content");
        assertThat(Files.list(tempDir).count()).isEqualTo(1);
        String name = Files.list(tempDir).findFirst().get().getFileName().toString();
        assertThat(name).matches("^[0-9a-fA-F\\-]{36}_\\d+\\.log$");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void write_filePermissions_ownerOnly() throws Exception {
        String traceId = UUID.randomUUID().toString();
        manager.write(traceId, "m", "sensitive data");

        Path file = Files.list(tempDir).findFirst().get();
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertThat(perms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        );
    }

    @Test
    void write_exceedsTotalSizeLimit_stops() throws Exception {
        DegradeFileManager smallMgr = new DegradeFileManager(tempDir, 100, 7, null);
        smallMgr.initialize();

        String content = repeat("x", 60);
        smallMgr.write(UUID.randomUUID().toString(), "m", content);
        smallMgr.write(UUID.randomUUID().toString(), "m", content);
        smallMgr.write(UUID.randomUUID().toString(), "m", content);

        long totalSize = Files.list(tempDir)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (Exception e) {
                        return 0L;
                    }
                })
                .sum();
        assertThat(totalSize).isLessThanOrEqualTo(200L);
    }

    @Test
    void cleanup_expiredFiles_deleted() throws Exception {
        Path oldFile = tempDir.resolve(UUID.randomUUID().toString() + ".log");
        Files.write(oldFile, "old content".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));

        String newId = UUID.randomUUID().toString();
        manager.write(newId, "m", "new content");

        DegradeFileManager.CleanupResult result = manager.cleanExpiredFiles();
        assertThat(result.getDeletedCount()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(Files.list(tempDir).count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void write_encryptEnabled_contentNotPlaintext() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();

        DegradeFileEncryptor encryptor = new DegradeFileEncryptor(key);
        DegradeFileManager encMgr = new DegradeFileManager(tempDir, 10 * 1024 * 1024, 7, encryptor);
        encMgr.initialize();

        String traceId = UUID.randomUUID().toString();
        String plainContent = "sensitive: 13812345678";
        encMgr.write(traceId, "m", plainContent);

        Path file = Files.list(tempDir).filter(Files::isRegularFile).findFirst().get();
        byte[] fileBytes = Files.readAllBytes(file);
        String rawContent = new String(fileBytes, StandardCharsets.UTF_8);
        assertThat(rawContent).doesNotContain("13812345678");
        assertThat(new String(encryptor.decrypt(fileBytes), StandardCharsets.UTF_8))
                .contains("13812345678");
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
