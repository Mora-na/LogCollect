package com.logcollect.api.exception;

public class LogCollectException extends RuntimeException {
    public LogCollectException(String message) { super(message); }
    public LogCollectException(String message, Throwable cause) { super(message, cause); }
    public LogCollectException(Throwable cause) { super(cause); }
}
