package com.logcollect.benchmark.jmh;

import com.logcollect.api.model.LogEntry;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JMH benchmark shared dataset.
 */
public final class BenchmarkData {

    public static final String MSG_CLEAN =
            "Order created successfully, orderId=ORD-20250701-0001, amount=99.50, status=PAID";

    public static final String MSG_WITH_SENSITIVE =
            "用户注册完成, 手机: 13812345678, 身份证: 110105199001011234, 邮箱: test@example.com";

    public static final String MSG_WITH_INJECTION =
            "Login user: admin\n2026-01-01 00:00:00 INFO FAKE_LOG payment=success amount=0\r\n<script>alert(1)</script>";

    public static final String MSG_LONG;

    public static final String THROWABLE_NORMAL;

    public static final Map<String, String> MDC_EMPTY = Collections.emptyMap();

    public static final Map<String, String> MDC_TYPICAL;

    public static final Map<String, String> MDC_LARGE;

    static {
        StringBuilder sb = new StringBuilder(40_000);
        sb.append("Processing batch data: [");
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("{\"id\":").append(i)
                    .append(",\"phone\":\"138").append(String.format("%08d", i))
                    .append("\",\"name\":\"user").append(i).append("\"}");
        }
        sb.append("]");
        MSG_LONG = sb.toString();
    }

    static {
        String throwable;
        try {
            simulateException();
            throwable = "";
        } catch (Exception e) {
            throwable = stackTraceToString(e);
        }
        THROWABLE_NORMAL = throwable;
    }

    static {
        Map<String, String> typical = new LinkedHashMap<String, String>();
        typical.put("traceId", "abc-123-def-456");
        typical.put("userId", "U10001");
        typical.put("requestId", "REQ-20250701-001");
        MDC_TYPICAL = Collections.unmodifiableMap(typical);

        Map<String, String> large = new LinkedHashMap<String, String>();
        for (int i = 0; i < 15; i++) {
            large.put("key" + i, "value" + i + (i == 7 ? "\ninjected" : ""));
        }
        MDC_LARGE = Collections.unmodifiableMap(large);
    }

    private BenchmarkData() {
    }

    public static LogEntry buildEntry(String content, Map<String, String> mdc) {
        return LogEntry.builder()
                .traceId("bench-trace-001")
                .content(content)
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("benchmark-thread-1")
                .loggerName("com.example.service.OrderService")
                .throwableString(null)
                .mdcContext(mdc)
                .build();
    }

    public static LogEntry buildEntryWithThrowable(String content) {
        return LogEntry.builder()
                .traceId("bench-trace-002")
                .content(content)
                .level("ERROR")
                .timestamp(System.currentTimeMillis())
                .threadName("benchmark-thread-1")
                .loggerName("com.example.service.OrderService")
                .throwableString(THROWABLE_NORMAL)
                .mdcContext(MDC_TYPICAL)
                .build();
    }

    private static String stackTraceToString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static void simulateException() throws Exception {
        try {
            level1();
        } catch (Exception e) {
            throw new RuntimeException("Top-level business error", e);
        }
    }

    private static void level1() throws Exception {
        try {
            level2();
        } catch (Exception e) {
            throw new RuntimeException("Service layer error", e);
        }
    }

    private static void level2() throws Exception {
        try {
            level3();
        } catch (Exception e) {
            throw new java.io.IOException("IO error during processing", e);
        }
    }

    private static void level3() {
        throw new IllegalArgumentException("Invalid parameter: id must be positive");
    }
}
