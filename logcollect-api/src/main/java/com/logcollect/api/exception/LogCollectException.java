package com.logcollect.api.exception;

/**
 * LogCollect 框架通用运行时异常。
 */
public class LogCollectException extends RuntimeException {
    public LogCollectException(String message) { super(message); }
    public LogCollectException(String message, Throwable cause) { super(message, cause); }
    public LogCollectException(Throwable cause) { super(cause); }
}
