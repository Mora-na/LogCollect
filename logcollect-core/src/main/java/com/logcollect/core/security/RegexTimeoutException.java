package com.logcollect.core.security;

/**
 * 正则匹配超时时抛出的控制流异常。
 */
final class RegexTimeoutException extends RuntimeException {

    RegexTimeoutException(String message) {
        super(message, null, false, false);
    }
}
