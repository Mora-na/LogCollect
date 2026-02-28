package com.logcollect.core.mdc;

import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;

public final class MDCAdapter {
    private MDCAdapter() {}

    public static void put(String key, String value) {
        try {
            MDC.put(key, value);
        } catch (Throwable ignore) {
        }
    }

    public static void remove(String key) {
        try {
            MDC.remove(key);
        } catch (Throwable ignore) {
        }
    }

    public static Map<String, String> getCopyOfContextMap() {
        try {
            Map<String, String> map = MDC.getCopyOfContextMap();
            return map == null ? Collections.<String, String>emptyMap() : map;
        } catch (Throwable ignore) {
            return Collections.<String, String>emptyMap();
        }
    }

    public static void setContextMap(Map<String, String> map) {
        try {
            MDC.setContextMap(map);
        } catch (Throwable ignore) {
        }
    }
}
