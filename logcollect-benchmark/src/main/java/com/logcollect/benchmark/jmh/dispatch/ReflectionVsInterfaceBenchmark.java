package com.logcollect.benchmark.jmh.dispatch;

import org.openjdk.jmh.annotations.*;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
public class ReflectionVsInterfaceBenchmark {

    public interface MetricsInterface {
        void incrementDiscarded(String methodKey, String reason);
    }

    public static class MetricsImpl implements MetricsInterface {
        private long count;

        @Override
        public void incrementDiscarded(String methodKey, String reason) {
            count++;
        }

        public long getCount() {
            return count;
        }
    }

    public static class NoopMetrics implements MetricsInterface {
        public static final NoopMetrics INSTANCE = new NoopMetrics();

        @Override
        public void incrementDiscarded(String methodKey, String reason) {
        }
    }

    private Object metricsAsObject;
    private MetricsInterface metricsAsInterface;
    private MetricsInterface noopMetrics;
    private Method cachedMethod;

    private final String methodKey = "OrderService#createOrder";
    private final String reason = "level_filter";

    @Setup
    public void setup() throws Exception {
        MetricsImpl impl = new MetricsImpl();
        metricsAsObject = impl;
        metricsAsInterface = impl;
        noopMetrics = NoopMetrics.INSTANCE;
        cachedMethod = MetricsImpl.class.getMethod("incrementDiscarded", String.class, String.class);
        cachedMethod.setAccessible(true);
    }

    @Benchmark
    public void reflection_getMethods_everyTime() {
        invokeReflective(metricsAsObject, "incrementDiscarded", methodKey, reason);
    }

    @Benchmark
    public void reflection_cachedMethod() throws Exception {
        cachedMethod.invoke(metricsAsObject, methodKey, reason);
    }

    @Benchmark
    public void interface_virtualDispatch() {
        metricsAsInterface.incrementDiscarded(methodKey, reason);
    }

    @Benchmark
    public void interface_noop() {
        noopMetrics.incrementDiscarded(methodKey, reason);
    }

    @SuppressWarnings("all")
    private Object invokeReflective(Object target, String methodName, Object... args) {
        try {
            Method[] methods = target.getClass().getMethods();
            for (Method method : methods) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length != args.length) {
                    continue;
                }
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (args[i] != null && !wrap(paramTypes[i]).isAssignableFrom(args[i].getClass())) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        return type;
    }
}
