package com.logcollect.autoconfigure.management;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 管理端写操作审计日志。
 *
 * <p>审计日志写入独立文件，不受 degrade 文件清理接口影响。
 */
public class LogCollectManagementAuditLogger {
    private final Path auditFile;
    private final ReentrantLock lock = new ReentrantLock();

    public LogCollectManagementAuditLogger(String configuredPath) {
        String path = configuredPath;
        if (path == null || path.trim().isEmpty()) {
            path = System.getProperty("user.home") + "/.logcollect/audit/management-audit.log";
        }
        this.auditFile = Paths.get(path);
    }

    public void audit(String action,
                      String principal,
                      String remoteAddr,
                      boolean success,
                      String detail) {
        StringBuilder line = new StringBuilder(256);
        line.append(Instant.now()).append('|')
                .append(action == null ? "unknown" : action).append('|')
                .append("success=").append(success).append('|')
                .append("principal=").append(principal == null ? "anonymous" : principal).append('|')
                .append("remote=").append(remoteAddr == null ? "-" : remoteAddr).append('|')
                .append("detail=").append(detail == null ? "-" : detail)
                .append('\n');

        lock.lock();
        try {
            Files.createDirectories(auditFile.getParent());
            Files.write(auditFile,
                    line.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LogCollectInternalLogger.warn("Write management audit log failed: {}", auditFile, e);
        } finally {
            lock.unlock();
        }
    }

    public String getAuditFilePath() {
        return auditFile.toString();
    }
}
