package com.logcollect.core.degrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DegradeFileManagerBranchTest {

    @TempDir
    Path tempDir;

    @Test
    void privateMethods_coverPermissionAndSizeFallbackBranches() throws Exception {
        DegradeFileManager manager = new DegradeFileManager(tempDir, 200, 7, null);
        manager.initialize();

        Path normalFile = tempDir.resolve("plain.txt");
        Files.write(normalFile, "plain".getBytes(StandardCharsets.UTF_8));

        Method calculateTotalSize = DegradeFileManager.class.getDeclaredMethod("calculateTotalSize", Path.class);
        calculateTotalSize.setAccessible(true);
        long sizeOnFilePath = (Long) calculateTotalSize.invoke(manager, normalFile);
        assertThat(sizeOnFilePath).isZero();

        Method setDirPermissions = DegradeFileManager.class.getDeclaredMethod("setDirPermissions", Path.class);
        setDirPermissions.setAccessible(true);
        setDirPermissions.invoke(manager, tempDir.resolve("missing-dir").resolve("nested"));

        Method setFilePermissions = DegradeFileManager.class.getDeclaredMethod("setFilePermissions", Path.class);
        setFilePermissions.setAccessible(true);
        setFilePermissions.invoke(manager, tempDir.resolve("missing-file.log"));
    }

    @Test
    void ensureCapacity_branch_deleteAndStillExceed_throws() throws Exception {
        DegradeFileManager manager = new DegradeFileManager(tempDir, 140, 7, null);
        manager.initialize();

        String text = repeat("x", 80);
        manager.write(UUID.randomUUID().toString(), "m", text);
        Thread.sleep(3L);
        manager.write(UUID.randomUUID().toString(), "m", text);

        Method ensureCapacity = DegradeFileManager.class.getDeclaredMethod("ensureCapacity", long.class);
        ensureCapacity.setAccessible(true);
        assertThatThrownBy(() -> ensureCapacity.invoke(manager, 400L))
                .hasRootCauseInstanceOf(IOException.class);
    }

    @Test
    void scheduleAndListLambdas_coverSyntheticBranches() throws Exception {
        DegradeFileManager manager = new DegradeFileManager(tempDir, 10 * 1024 * 1024, 7, null);
        manager.initialize();
        manager.write(UUID.randomUUID().toString(), Arrays.asList("l1", "l2"));

        Method scheduleCleanup = DegradeFileManager.class.getDeclaredMethod("scheduleCleanup");
        scheduleCleanup.setAccessible(true);
        scheduleCleanup.invoke(manager);
        scheduleCleanup.invoke(manager);

        Method scheduleLambda = findDeclared("lambda$scheduleCleanup$", 0);
        scheduleLambda.invoke(manager);

        Method sortLambda = findDeclared("lambda$listFilesSortedByTime$", 1);
        long fallback = (Long) sortLambda.invoke(manager, tempDir.resolve("not-exist.log"));
        assertThat(fallback).isEqualTo(Long.MAX_VALUE);

        Method listFiles = DegradeFileManager.class.getDeclaredMethod("listFilesSortedByTime");
        listFiles.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Path> files = (List<Path>) listFiles.invoke(manager);
        assertThat(files).isNotEmpty();
    }

    @Test
    void getDiskFreeSpace_parentMissingBranch_returnsMinusOne() {
        DegradeFileManager manager = new DegradeFileManager(Paths.get("single-segment-path"), 1024, 1, null);
        assertThat(manager.getDiskFreeSpace()).isEqualTo(-1L);
    }

    private Method findDeclared(String prefix, int paramCount) {
        for (Method method : DegradeFileManager.class.getDeclaredMethods()) {
            if (method.getName().startsWith(prefix) && method.getParameterCount() == paramCount) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("method not found: " + prefix);
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
