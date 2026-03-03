package com.logcollect.autoconfigure.circuitbreaker;

import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.util.MethodKeyResolver;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CircuitBreakerRegistry {

    private final ConcurrentHashMap<String, LogCollectCircuitBreaker> registry =
            new ConcurrentHashMap<String, LogCollectCircuitBreaker>();

    public void register(String methodKey, LogCollectCircuitBreaker breaker) {
        register(methodKey, breaker, null);
    }

    public void register(String methodKey, LogCollectCircuitBreaker breaker, LogCollectMetrics metrics) {
        if (methodKey == null || breaker == null) {
            return;
        }
        final String displayKey = MethodKeyResolver.normalize(methodKey);
        if (metrics != null) {
            breaker.setRecoveryCallback(() -> metrics.incrementCircuitRecovered(displayKey));
        }
        registry.put(displayKey, breaker);
    }

    public void register(Method method, LogCollectCircuitBreaker breaker, LogCollectMetrics metrics) {
        if (method == null || breaker == null) {
            return;
        }
        register(MethodKeyResolver.toDisplayKey(method), breaker, metrics);
    }

    public LogCollectCircuitBreaker get(String methodKey) {
        if (methodKey == null) {
            return null;
        }
        String normalized = MethodKeyResolver.normalize(methodKey);
        LogCollectCircuitBreaker breaker = registry.get(normalized);
        if (breaker == null) {
            breaker = registry.get(methodKey);
        }
        return breaker;
    }

    public Map<String, LogCollectCircuitBreaker> getAll() {
        return Collections.unmodifiableMap(registry);
    }

    public Set<String> getAllMethodKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    public boolean contains(String methodKey) {
        return get(methodKey) != null;
    }

    public static String buildMethodKey(Method method) {
        return MethodKeyResolver.toDisplayKey(method);
    }
}
