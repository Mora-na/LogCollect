package com.logcollect.core.security;

import com.logcollect.core.internal.LogCollectInternalLogger;

import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RegexSafetyValidator {
    private static final int MAX_REGEX_LENGTH = 500;
    private static final long REGEX_TEST_TIMEOUT_MS = 100;
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^)]*[+*][^)]*\\)\\s*[+*]");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "logcollect-regex-validator");
            t.setDaemon(true);
            return t;
        }
    });

    private RegexSafetyValidator() {}

    public static void validate(Pattern pattern) {
        if (pattern == null) {
            throw new NullPointerException("pattern");
        }
        String regex = pattern.pattern();
        if (regex == null || regex.isEmpty()) {
            return;
        }
        if (!isSafe(regex)) {
            throw new IllegalArgumentException("ReDoS risk regex rejected: " + regex);
        }
    }

    public static boolean isSafe(String regex) {
        if (regex == null) {
            return false;
        }
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (regex.isEmpty()) {
            return true;
        }
        if (regex.length() > MAX_REGEX_LENGTH) {
            return false;
        }
        Matcher nestedQuantifierMatcher = NESTED_QUANTIFIER.matcher(regex);
        if (nestedQuantifierMatcher.find()) {
            return false;
        }
        try {
            final Pattern pattern = Pattern.compile(regex);
            Future<Boolean> future = EXECUTOR.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    String evil = "aaaaaaaaaaaaaaaaaaaa!";
                    return pattern.matcher(evil).matches();
                }
            });
            try {
                if (Thread.currentThread().isInterrupted()) {
                    future.cancel(true);
                    return false;
                }
                future.get(REGEX_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                return false;
            }
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Regex validation error", e);
            return false;
        } catch (Error e) {
            throw e;
        }
    }

    public static Pattern safeCompile(String regex) {
        try {
            Pattern pattern = Pattern.compile(regex);
            validate(pattern);
            return pattern;
        } catch (PatternSyntaxException e) {
            LogCollectInternalLogger.warn("Invalid regex: {}", regex, e);
            return null;
        } catch (IllegalArgumentException e) {
            LogCollectInternalLogger.warn("Rejected unsafe regex: {}", regex);
            return null;
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Compile regex failed: {}", regex, e);
            return null;
        }
    }
}
