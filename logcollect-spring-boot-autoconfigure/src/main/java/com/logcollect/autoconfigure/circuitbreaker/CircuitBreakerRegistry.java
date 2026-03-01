package com.logcollect.autoconfigure.circuitbreaker;

import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CircuitBreakerRegistry {

    private final ConcurrentHashMap<String, LogCollectCircuitBreaker> registry =
            new ConcurrentHashMap<String, LogCollectCircuitBreaker>();

    public void register(String methodKey, LogCollectCircuitBreaker breaker) {
        if (methodKey == null || breaker == null) {
            return;
        }
        registry.put(methodKey, breaker);
    }

    public LogCollectCircuitBreaker get(String methodKey) {
        return registry.get(methodKey);
    }

    public Map<String, LogCollectCircuitBreaker> getAll() {
        return Collections.unmodifiableMap(registry);
    }

    public Set<String> getAllMethodKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    public boolean contains(String methodKey) {
        return registry.containsKey(methodKey);
    }

    public static String buildMethodKey(Method method) {
        return method.getDeclaringClass().getName().replace('.', '_') + "_" + method.getName();
    }
}
