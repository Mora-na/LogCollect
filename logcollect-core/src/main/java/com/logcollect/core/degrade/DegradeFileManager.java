package com.logcollect.core.degrade;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class DegradeFileManager {
    private static final long DISK_FREE_MIN_BYTES = 100L * 1024 * 1024;

    private final Path baseDir;
    private final long maxTotalBytes;
    private final int ttlDays;
    private final DegradeFileEncryptor encryptor;

    public DegradeFileManager(Path baseDir, long maxTotalBytes, int ttlDays, DegradeFileEncryptor encryptor) {
        this.baseDir = baseDir;
        this.maxTotalBytes = maxTotalBytes;
        this.ttlDays = ttlDays;
        this.encryptor = encryptor;
    }

    public void write(String traceId, List<String> logLines) {
        try {
            if (baseDir == null) {
                return;
            }
            Files.createDirectories(baseDir);
            FileStore store = Files.getFileStore(baseDir);
            if (store.getUsableSpace() < DISK_FREE_MIN_BYTES) {
                LogCollectInternalLogger.warn("Degrade disk space too low, skip write");
                return;
            }
            String fileName = traceId + "_" + System.currentTimeMillis() + ".log";
            Path file = baseDir.resolve(fileName);
            byte[] content = buildContent(logLines);
            ensureCapacity(content.length);
            Files.createFile(file);
            setFilePermissions(file);
            if (encryptor != null) {
                content = encryptor.encrypt(content);
            }
            Files.write(file, content);
        } catch (Throwable t) {
            LogCollectInternalLogger.error("Degrade file write failed", t);
            throw new DegradeStorageException(t);
        }
    }

    private byte[] buildContent(List<String> logLines) {
        if (logLines == null || logLines.isEmpty()) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        for (String line : logLines) {
            sb.append(line).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void ensureCapacity(long incomingBytes) throws IOException {
        if (maxTotalBytes <= 0) {
            return;
        }
        List<Path> files = listFiles();
        long total = 0;
        for (Path f : files) {
            total += Files.size(f);
        }
        if (total + incomingBytes <= maxTotalBytes) {
            return;
        }
        files.sort(new Comparator<Path>() {
            @Override
            public int compare(Path o1, Path o2) {
                try {
                    long t1 = Files.getLastModifiedTime(o1).toMillis();
                    long t2 = Files.getLastModifiedTime(o2).toMillis();
                    return Long.compare(t1, t2);
                } catch (IOException e) {
                    return 0;
                }
            }
        });
        for (Path f : files) {
            long size = Files.size(f);
            Files.deleteIfExists(f);
            total -= size;
            if (total + incomingBytes <= maxTotalBytes) {
                break;
            }
        }
    }

    private List<Path> listFiles() throws IOException {
        List<Path> files = new ArrayList<Path>();
        if (!Files.exists(baseDir)) {
            return files;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        return files;
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
                    List<AclEntry> acl = new ArrayList<AclEntry>();
                    acl.add(entry);
                    view.setAcl(acl);
                }
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Failed to set file permissions on: {}", file, t);
        }
    }

    public CleanupResult cleanExpiredFiles() {
        int deletedCount = 0;
        long deletedBytes = 0;
        try {
            if (baseDir == null || !Files.exists(baseDir)) {
                return new CleanupResult(0, 0);
            }
            Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    try {
                        Instant lastModified = Files.getLastModifiedTime(file).toInstant();
                        if (lastModified.isBefore(cutoff)) {
                            long size = Files.size(file);
                            Files.deleteIfExists(file);
                            deletedCount++;
                            deletedBytes += size;
                        }
                    } catch (Throwable t) {
                        LogCollectInternalLogger.warn("Failed to delete expired file: {}", file, t);
                    }
                }
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Expired file cleanup error", t);
        }
        return new CleanupResult(deletedCount, deletedBytes);
    }

    public CleanupResult cleanAllFiles() {
        int deletedCount = 0;
        long deletedBytes = 0;
        try {
            if (baseDir == null || !Files.exists(baseDir)) {
                return new CleanupResult(0, 0);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    try {
                        long size = Files.size(file);
                        Files.deleteIfExists(file);
                        deletedCount++;
                        deletedBytes += size;
                    } catch (Throwable t) {
                        LogCollectInternalLogger.warn("Failed to delete degrade file: {}", file, t);
                    }
                }
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Cleanup all files error", t);
        }
        return new CleanupResult(deletedCount, deletedBytes);
    }

    public long getFileCount() {
        if (baseDir == null || !Files.exists(baseDir)) {
            return 0;
        }
        long count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    count++;
                }
            }
        } catch (IOException e) {
            LogCollectInternalLogger.warn("Failed to count degrade files", e);
        }
        return count;
    }

    public long getTotalSizeBytes() {
        if (baseDir == null || !Files.exists(baseDir)) {
            return 0;
        }
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    total += Files.size(file);
                }
            }
        } catch (IOException e) {
            LogCollectInternalLogger.warn("Failed to sum degrade file sizes", e);
        }
        return total;
    }

    public String getTotalSizeHuman() {
        return formatBytes(getTotalSizeBytes());
    }

    public long getDiskFreeSpace() {
        if (baseDir == null) {
            return -1;
        }
        try {
            Path storePath = Files.exists(baseDir) ? baseDir : baseDir.getParent();
            if (storePath == null) {
                return -1;
            }
            return Files.getFileStore(storePath).getUsableSpace();
        } catch (IOException e) {
            LogCollectInternalLogger.warn("Failed to get disk free space", e);
            return -1;
        }
    }

    public String getDiskFreeSpaceHuman() {
        return formatBytes(getDiskFreeSpace());
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

    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
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
