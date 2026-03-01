package com.logcollect.core.degrade;

import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.util.DataSizeParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DegradeFileManager {

    private static final long DISK_FREE_MIN_BYTES = 100L * 1024 * 1024;

    private final Path baseDir;
    private final long maxTotalBytes;
    private final int ttlDays;
    private final DegradeFileEncryptor encryptor;

    private volatile boolean initialized;
    private final AtomicLong currentTotalSize = new AtomicLong(0);
    private ScheduledExecutorService scheduler;

    public DegradeFileManager(Path baseDir, long maxTotalBytes, int ttlDays, DegradeFileEncryptor encryptor) {
        this.baseDir = baseDir;
        this.maxTotalBytes = maxTotalBytes;
        this.ttlDays = ttlDays;
        this.encryptor = encryptor;
    }

    public void initialize() {
        try {
            if (baseDir == null) {
                return;
            }
            Files.createDirectories(baseDir);
            setDirPermissions(baseDir);
            currentTotalSize.set(calculateTotalSize(baseDir));
            scheduleCleanup();
            initialized = true;
            LogCollectInternalLogger.info("DegradeFileManager initialized: dir={}, currentSize={}, maxSize={}",
                    baseDir,
                    DataSizeParser.formatBytes(currentTotalSize.get()),
                    DataSizeParser.formatBytes(maxTotalBytes));
        } catch (Throwable t) {
            LogCollectInternalLogger.error("Initialize DegradeFileManager failed", t);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void write(String traceId, List<String> logLines) {
        StringBuilder builder = new StringBuilder();
        if (logLines != null) {
            for (String line : logLines) {
                builder.append(line).append('\n');
            }
        }
        write(traceId, "unknown", builder.toString());
    }

    public void write(String traceId, String methodSignature, String content) {
        if (!initialized) {
            throw new IllegalStateException("DegradeFileManager not initialized");
        }
        try {
            if (baseDir == null) {
                return;
            }
            FileStore store = Files.getFileStore(baseDir);
            if (store.getUsableSpace() < DISK_FREE_MIN_BYTES) {
                throw new IOException("Disk free space too low: " + DataSizeParser.formatBytes(store.getUsableSpace()));
            }

            byte[] data = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            ensureCapacity(data.length);

            String safeTrace = traceId == null || traceId.trim().isEmpty() ? UUID.randomUUID().toString() : traceId;
            String fileName = safeTrace + "_" + System.currentTimeMillis() + ".log";
            Path file = baseDir.resolve(fileName);

            byte[] finalData = encryptor == null ? data : encryptor.encrypt(data);
            Files.write(file, finalData, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setFilePermissions(file);
            currentTotalSize.addAndGet(finalData.length);
        } catch (Throwable t) {
            LogCollectInternalLogger.error("Degrade file write failed", t);
            throw new DegradeStorageException(t);
        }
    }

    public CleanupResult cleanExpiredFiles() {
        return cleanup(false);
    }

    public CleanupResult cleanAllFiles() {
        return cleanup(true);
    }

    public CleanupResult cleanup(boolean force) {
        int deletedCount = 0;
        long deletedBytes = 0;
        if (baseDir == null || !Files.exists(baseDir)) {
            return new CleanupResult(0, 0);
        }

        long ttlMs = ttlDays * 24L * 3600 * 1000;
        long now = System.currentTimeMillis();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "*.log")) {
            for (Path file : stream) {
                try {
                    long fileSize = Files.size(file);
                    boolean expired = now - Files.getLastModifiedTime(file).toMillis() > ttlMs;
                    if (force || expired) {
                        if (Files.deleteIfExists(file)) {
                            deletedCount++;
                            deletedBytes += fileSize;
                            currentTotalSize.addAndGet(-fileSize);
                        }
                    }
                } catch (Throwable t) {
                    LogCollectInternalLogger.warn("Delete degrade file failed: {}", file, t);
                }
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Cleanup degrade files failed", t);
        }

        if (currentTotalSize.get() < 0) {
            currentTotalSize.set(0);
        }
        return new CleanupResult(deletedCount, deletedBytes);
    }

    public long getFileCount() {
        if (baseDir == null || !Files.exists(baseDir)) {
            return 0;
        }
        long count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "*.log")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    count++;
                }
            }
        } catch (IOException e) {
            LogCollectInternalLogger.warn("Count degrade files failed", e);
        }
        return count;
    }

    public long getTotalSizeBytes() {
        return Math.max(0, currentTotalSize.get());
    }

    public String getTotalSizeHuman() {
        return DataSizeParser.formatBytes(getTotalSizeBytes());
    }

    public long getDiskFreeSpace() {
        if (baseDir == null) {
            return -1;
        }
        try {
            Path path = Files.exists(baseDir) ? baseDir : baseDir.getParent();
            if (path == null) {
                return -1;
            }
            return Files.getFileStore(path).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    public String getDiskFreeSpaceHuman() {
        return DataSizeParser.formatBytes(getDiskFreeSpace());
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public int getTtlDays() {
        return ttlDays;
    }

    private void ensureCapacity(long incomingBytes) throws IOException {
        if (maxTotalBytes <= 0) {
            return;
        }
        if (currentTotalSize.get() + incomingBytes <= maxTotalBytes) {
            return;
        }

        cleanup(false);
        if (currentTotalSize.get() + incomingBytes <= maxTotalBytes) {
            return;
        }

        List<Path> files = listFilesSortedByTime();
        for (Path file : files) {
            if (currentTotalSize.get() + incomingBytes <= maxTotalBytes) {
                break;
            }
            long size = Files.size(file);
            if (Files.deleteIfExists(file)) {
                currentTotalSize.addAndGet(-size);
            }
        }

        if (currentTotalSize.get() + incomingBytes > maxTotalBytes) {
            throw new IOException("Degrade file total size limit exceeded");
        }
    }

    private List<Path> listFilesSortedByTime() throws IOException {
        List<Path> files = new ArrayList<Path>();
        if (!Files.exists(baseDir)) {
            return files;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".log"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparingLong(path -> {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return Long.MAX_VALUE;
            }
        }));
        return files;
    }

    private long calculateTotalSize(Path dir) {
        long total = 0;
        if (dir == null || !Files.exists(dir)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(path)) {
                    total += Files.size(path);
                }
            }
        } catch (IOException e) {
            LogCollectInternalLogger.warn("Calculate degrade file size failed", e);
        }
        return total;
    }

    private void scheduleCleanup() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "logcollect-degrade-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanup(false);
            } catch (Throwable ignored) {
            }
        }, 1, 6, TimeUnit.HOURS);
    }

    private void setDirPermissions(Path dir) {
        try {
            if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return;
            }
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(dir, perms);
        } catch (Throwable ignored) {
        }
    }

    private void setFilePermissions(Path file) {
        try {
            FileSystem fs = FileSystems.getDefault();
            if (fs.supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(file, perms);
            } else if (fs.supportedFileAttributeViews().contains("acl")) {
                AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
                if (view != null) {
                    UserPrincipal owner = view.getOwner();
                    AclEntry entry = AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA))
                            .build();
                    view.setAcl(java.util.Collections.singletonList(entry));
                }
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Set degrade file permissions failed: {}", file, t);
        }
    }

    public static class CleanupResult {
        private final int deletedCount;
        private final long deletedBytes;

        public CleanupResult(int deletedCount, long deletedBytes) {
            this.deletedCount = deletedCount;
            this.deletedBytes = deletedBytes;
        }

        public int getDeletedCount() {
            return deletedCount;
        }

        public long getDeletedBytes() {
            return deletedBytes;
        }
    }
}
