package com.logcollect.core.degrade;

/**
 * 降级存储异常。
 */
public class DegradeStorageException extends RuntimeException {
    /**
     * 使用根因创建异常。
     *
     * @param cause 根因
     */
    public DegradeStorageException(Throwable cause) {
        super(cause);
    }
}
