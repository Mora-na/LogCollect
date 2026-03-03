package com.logcollect.benchmark.jmh.dispatch;

import org.openjdk.jmh.annotations.*;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class InvokeReflectiveBenchmark {

    private Object target;
    private Method cached;

    @Setup
    public void setup() throws Exception {
        target = new ReflectionVsInterfaceBenchmark.MetricsImpl();
        cached = ReflectionVsInterfaceBenchmark.MetricsImpl.class
                .getMethod("incrementDiscarded", String.class, String.class);
        cached.setAccessible(true);
    }

    @Benchmark
    public Object reflective_lookupAndInvoke() {
        return invokeReflective(target, "incrementDiscarded", "m", "r");
    }

    @Benchmark
    public Object reflective_cachedInvoke() throws Exception {
        return cached.invoke(target, "m", "r");
    }

    private Object invokeReflective(Object targetObj, String methodName, Object... args) {
        try {
            Method[] methods = targetObj.getClass().getMethods();
            for (Method method : methods) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getParameterTypes().length != args.length) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(targetObj, args);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
