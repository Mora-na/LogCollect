package com.logcollect.api.exception;

public class LogCollectDegradeException extends LogCollectException {
    public LogCollectDegradeException(String message) {
        super(message);
    }

    public LogCollectDegradeException(String message, Throwable cause) {
        super(message, cause);
    }
}
