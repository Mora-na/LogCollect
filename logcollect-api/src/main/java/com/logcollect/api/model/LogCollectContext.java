package com.logcollect.api.model;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次 {@code @LogCollect} 调用的生命周期上下文。
 *
 * <p>该对象会在一次方法执行过程中持续存在，并在以下阶段复用同一个实例：
 * {@code before -> 日志收集 -> append/flush -> after}。
 * 用户可在 {@link com.logcollect.api.handler.LogCollectHandler} 的各阶段中读取上下文信息，
 * 也可通过 {@link #setBusinessId(Object)} / {@link #setAttribute(String, Object)} 在阶段间传递业务数据。
 *
 * <p>线程安全策略：
 * 不可变元数据使用 {@code final}；可变状态使用 {@code volatile}、原子计数器或并发容器维护。
 */
public class LogCollectContext {
    /** 本次调用的唯一追踪 ID（通常为 UUID）。 */
    private final String traceId;
    /** 被拦截方法的全限定签名，格式：{@code 全限定类名#方法名}。 */
    private final String methodSignature;
    /** 被拦截方法所属类名（短名，不含包名）。 */
    private final String className;
    /** 被拦截方法名。 */
    private final String methodName;
    /** 被拦截方法的反射对象。 */
    private final Method method;
    /** 方法入参数组快照（浅拷贝，避免外部对原数组引用产生副作用）。 */
    private final Object[] methodArgs;
    /** 方法开始时间（本地时间对象，便于业务直接落库）。 */
    private final LocalDateTime startTime;
    /** 方法开始时间戳（毫秒），用于计算耗时。 */
    private final long startTimeMillis;
    /** 本次实际采用的收集模式。 */
    private final CollectMode collectMode;
    /** 处理器实现类类型，便于诊断和审计。 */
    private final Class<?> handlerClass;

    /** 本次调用解析后的配置快照。 */
    private final LogCollectConfig config;
    /** 当前调用对应的 Handler 实例。 */
    private final LogCollectHandler handler;
    /** 当前调用绑定的缓冲区实例（SINGLE/AGGREGATE 的实现不同）。 */
    private final Object buffer;
    /** Pipeline 队列（V2 双阶段流水线）。 */
    private volatile Object pipelineQueue;
    /** Pipeline Consumer（用于关闭交接）。 */
    private volatile Object pipelineConsumer;
    /** 当前调用绑定的熔断器实例。 */
    private final Object circuitBreaker;
    /** 当前最低采集级别。 */
    private final int minLevelInt;
    /** 当前排除的 logger 前缀。 */
    private final String[] excludeLoggers;

    /** 业务方法正常结束时的返回值。 */
    private volatile Object returnValue;
    /** 业务方法异常结束时抛出的异常。 */
    private volatile Throwable error;

    /** 本次调用累计收集到的日志条数。 */
    private final AtomicInteger totalCollectedCount = new AtomicInteger(0);
    /** 本次调用累计被丢弃的日志条数（过滤、降级等导致）。 */
    private final AtomicInteger totalDiscardedCount = new AtomicInteger(0);
    /** 本次调用累计收集的日志字节数（估算值）。 */
    private final AtomicLong totalCollectedBytes = new AtomicLong(0);
    /** 本次调用累计 flush 次数。 */
    private final AtomicInteger flushCount = new AtomicInteger(0);
    /** 方法结束后标记 closing，通知 Consumer 停止处理。 */
    private volatile boolean closing;
    /** 方法生命周期结束标记。 */
    private volatile boolean closed;
    /** Consumer 是否正在处理当前 context 的记录。 */
    private volatile boolean consumerProcessing;

    /**
     * 业务侧主键/关联 ID。
     *
     * <p>通常在 {@code handler.before()} 中设置，后续在 {@code append/flush/after} 中复用。
     */
    private volatile Object businessId;
    /**
     * 用户自定义属性容器，用于跨阶段共享临时数据。
     *
     * <p>建议存放轻量对象；避免超大对象以降低一次调用期间的内存压力。
     */
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<String, Object>();

    /**
     * 构造上下文。
     *
     * @param traceId        追踪 ID
     * @param method         被拦截方法的反射对象
     * @param args           方法入参（会复制为快照）
     * @param config         当前调用使用的配置
     * @param handler        当前调用使用的处理器
     * @param buffer         当前调用绑定的缓冲区
     * @param circuitBreaker 当前调用绑定的熔断器
     * @param collectMode    当前调用的收集模式
     */
    public LogCollectContext(String traceId,
                             Method method,
                             Object[] args,
                             LogCollectConfig config,
                             LogCollectHandler handler,
                             Object buffer,
                             Object circuitBreaker,
                             CollectMode collectMode) {
        this.traceId = traceId;
        this.method = method;
        this.methodArgs = args == null ? new Object[0] : Arrays.copyOf(args, args.length);
        this.className = method == null ? "" : method.getDeclaringClass().getSimpleName();
        this.methodName = method == null ? "" : method.getName();
        this.methodSignature = method == null ? "" :
                (method.getDeclaringClass().getName() + "#" + method.getName());
        this.startTime = LocalDateTime.now();
        this.startTimeMillis = System.currentTimeMillis();
        this.collectMode = collectMode == null ? CollectMode.AUTO : collectMode;
        this.handlerClass = handler == null ? null : handler.getClass();
        this.config = config;
        this.handler = handler;
        this.buffer = buffer;
        this.circuitBreaker = circuitBreaker;
        this.minLevelInt = toLevelInt(config == null ? "TRACE" : config.getLevel());
        this.excludeLoggers = config == null
                ? new String[0]
                : config.getExcludeLoggerPrefixes();
    }

    /**
     * @return 本次调用 traceId
     */
    public String getTraceId() { return traceId; }

    /**
     * @return 被拦截方法签名（全限定类名#方法名）
     */
    public String getMethodSignature() { return methodSignature; }

    /**
     * @return 被拦截类短名
     */
    public String getClassName() { return className; }

    /**
     * @return 被拦截方法名
     */
    public String getMethodName() { return methodName; }

    /**
     * @return 被拦截方法反射对象
     */
    public Method getMethod() { return method; }

    /**
     * 获取方法参数快照副本。
     *
     * <p>每次调用都会返回新数组，避免调用方意外修改内部状态。
     *
     * @return 方法参数快照副本
     */
    public Object[] getMethodArgs() { return Arrays.copyOf(methodArgs, methodArgs.length); }

    /**
     * @return 方法开始时间（LocalDateTime）
     */
    public LocalDateTime getStartTime() { return startTime; }

    /**
     * @return 方法开始时间戳（毫秒）
     */
    public long getStartTimeMillis() { return startTimeMillis; }

    /**
     * @return 本次调用实际采用的收集模式
     */
    public CollectMode getCollectMode() { return collectMode; }

    /**
     * @return 处理器实现类
     */
    public Class<?> getHandlerClass() { return handlerClass; }

    /**
     * @return 当前调用配置
     */
    public LogCollectConfig getConfig() { return config; }

    /**
     * @return 当前调用处理器实例
     */
    public LogCollectHandler getHandler() { return handler; }

    /**
     * @return 当前调用缓冲区实例（内部对象，通常仅框架内部使用）
     */
    public Object getBuffer() { return buffer; }

    public Object getPipelineQueue() { return pipelineQueue; }

    public void setPipelineQueue(Object pipelineQueue) { this.pipelineQueue = pipelineQueue; }

    public Object getPipelineConsumer() { return pipelineConsumer; }

    public void setPipelineConsumer(Object pipelineConsumer) { this.pipelineConsumer = pipelineConsumer; }

    /**
     * @return 当前调用熔断器实例（内部对象，通常仅框架内部使用）
     */
    public Object getCircuitBreaker() { return circuitBreaker; }

    /**
     * @return 当前最低采集级别（数值）
     */
    public int getMinLevelInt() { return minLevelInt; }

    /**
     * 按前缀判断 logger 是否应排除。
     *
     * @param loggerName 待判断的 logger 名称
     * @return true 表示命中排除前缀
     */
    public boolean isLoggerExcluded(String loggerName) {
        if (loggerName == null || excludeLoggers.length == 0) {
            return false;
        }
        for (String prefix : excludeLoggers) {
            if (prefix != null && !prefix.isEmpty() && loggerName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return 业务方法返回值；若异常结束则通常为 null
     */
    public Object getReturnValue() { return returnValue; }

    /**
     * 设置业务方法返回值（由框架在 AOP finally 阶段写入）。
     *
     * @param returnValue 返回值
     */
    public void setReturnValue(Object returnValue) { this.returnValue = returnValue; }

    /**
     * @return 业务方法异常对象；若正常结束则为 null
     */
    public Throwable getError() { return error; }

    /**
     * 设置业务方法异常（由框架在 AOP finally 阶段写入）。
     *
     * @param error 异常对象
     */
    public void setError(Throwable error) { this.error = error; }

    /**
     * @return true 表示本次业务方法异常结束
     */
    public boolean hasError() { return error != null; }

    /**
     * 获取从方法开始到当前时刻的耗时。
     *
     * @return 已执行毫秒数
     */
    public long getElapsedMillis() { return System.currentTimeMillis() - startTimeMillis; }

    /**
     * @return 已收集日志条数
     */
    public int getTotalCollectedCount() { return totalCollectedCount.get(); }

    /**
     * @return 已丢弃日志条数
     */
    public int getTotalDiscardedCount() { return totalDiscardedCount.get(); }

    /**
     * @return 已收集日志字节数（估算）
     */
    public long getTotalCollectedBytes() { return totalCollectedBytes.get(); }

    /**
     * @return 已 flush 次数
     */
    public int getFlushCount() { return flushCount.get(); }

    /**
     * 收集条数加 1（框架在成功入缓冲后调用）。
     */
    public void incrementCollectedCount() { totalCollectedCount.incrementAndGet(); }

    /**
     * 收集条数增加。
     *
     * @param delta 增量（可为负数，不建议传负数）
     */
    public void incrementCollectedCount(int delta) { totalCollectedCount.addAndGet(delta); }

    /**
     * 丢弃条数加 1（框架在过滤或降级丢弃时调用）。
     */
    public void incrementDiscardedCount() { totalDiscardedCount.incrementAndGet(); }

    /**
     * 丢弃条数增加。
     *
     * @param delta 增量（可为负数，不建议传负数）
     */
    public void incrementDiscardedCount(int delta) { totalDiscardedCount.addAndGet(delta); }

    /**
     * 增加已收集字节数。
     *
     * @param bytes 新增字节数
     */
    public void addCollectedBytes(long bytes) { totalCollectedBytes.addAndGet(bytes); }

    /**
     * flush 次数加 1（框架每次真正执行 flush 后调用）。
     */
    public void incrementFlushCount() { flushCount.incrementAndGet(); }

    public boolean isClosing() { return closing; }

    public void markClosing() { this.closing = true; }

    public boolean isClosed() { return closed; }

    public void markClosed() { this.closed = true; }

    public boolean isConsumerProcessing() { return consumerProcessing; }

    public void setConsumerProcessing(boolean processing) { this.consumerProcessing = processing; }

    /**
     * @deprecated Internal ready-queue flag has been removed since v2.2.1 (Lazy Signal). Kept for binary compatibility.
     */
    @Deprecated
    public boolean markPipelineReady() {
        return true;
    }

    /**
     * @deprecated Internal ready-queue flag has been removed since v2.2.1 (Lazy Signal). Kept for binary compatibility.
     */
    @Deprecated
    public void clearPipelineReady() {
        // no-op
    }

    /**
     * @return 业务 ID（原始对象）
     */
    public Object getBusinessId() { return businessId; }

    /**
     * 获取指定类型的业务ID。
     *
     * <p>若实际类型不匹配会抛出 {@link ClassCastException}。
     *
     * @param type 目标类型
     * @param <T>  类型参数
     * @return 指定类型的业务 ID
     */
    @SuppressWarnings("unchecked")
    public <T> T getBusinessId(Class<T> type) {
        return type.cast(businessId);
    }

    /**
     * 设置业务ID。
     *
     * @param businessId 业务 ID（可为任意可安全共享对象）
     */
    public void setBusinessId(Object businessId) { this.businessId = businessId; }

    /**
     * 设置自定义属性。
     *
     * <p>注意：底层为 {@link ConcurrentHashMap}，key/value 不能为 {@code null}。
     *
     * @param key   属性名
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取自定义属性。
     *
     * @param key 属性名
     * @return 属性值；不存在时返回 null
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * 获取指定类型的自定义属性。
     *
     * <p>若属性存在但类型不匹配会抛出 {@link ClassCastException}。
     *
     * @param key  属性名
     * @param type 目标类型
     * @param <T>  类型参数
     * @return 指定类型属性值；不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        return type.cast(attributes.get(key));
    }

    /**
     * 判断属性是否存在。
     *
     * @param key 属性名
     * @return true 表示属性存在（即使值为空字符串等）
     */
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    // ================================================================
    //  新增：静态访问器（业务代码直接调用）
    // ================================================================

    /**
     * 获取当前线程栈顶上下文。
     *
     * @return 栈顶上下文；若当前不在 {@code @LogCollect} 作用范围内则返回 null
     */
    public static LogCollectContext current() {
        return invokeManagerCurrent();
    }

    /**
     * 判断当前线程是否处于日志收集范围内。
     *
     * @return true 表示当前存在活跃上下文
     */
    public static boolean isActive() {
        return current() != null;
    }

    // ---------- businessId 静态便捷方法 ----------

    /**
     * 在当前上下文中设置业务 ID。
     *
     * <p>若当前线程不在收集范围内，静默忽略，不抛异常。
     *
     * @param businessId 业务 ID
     */
    public static void setCurrentBusinessId(Object businessId) {
        LogCollectContext ctx = current();
        if (ctx != null) {
            ctx.setBusinessId(businessId);
        }
    }

    /**
     * 在当前上下文中读取业务 ID。
     *
     * @param type 目标类型
     * @param <T>  类型参数
     * @return 业务 ID；若不在收集范围内返回 null
     */
    public static <T> T getCurrentBusinessId(Class<T> type) {
        LogCollectContext ctx = current();
        return ctx != null ? ctx.getBusinessId(type) : null;
    }

    // ---------- attribute 静态便捷方法 ----------

    /**
     * 在当前上下文中写入自定义属性。
     *
     * <p>若当前线程不在收集范围内，静默忽略，不抛异常。
     *
     * @param key   属性名
     * @param value 属性值
     */
    public static void setCurrentAttribute(String key, Object value) {
        LogCollectContext ctx = current();
        if (ctx != null) {
            ctx.setAttribute(key, value);
        }
    }

    /**
     * 在当前上下文中读取指定类型的属性值。
     *
     * @param key  属性名
     * @param type 目标类型
     * @param <T>  类型参数
     * @return 属性值；若不在收集范围内或属性不存在返回 null
     */
    public static <T> T getCurrentAttribute(String key, Class<T> type) {
        LogCollectContext ctx = current();
        return ctx != null ? ctx.getAttribute(key, type) : null;
    }

    /**
     * 在当前上下文中读取原始类型属性值。
     *
     * @param key 属性名
     * @return 属性值；若不在收集范围内或属性不存在返回 null
     */
    public static Object getCurrentAttribute(String key) {
        LogCollectContext ctx = current();
        return ctx != null ? ctx.getAttribute(key) : null;
    }

    /**
     * 判断当前上下文是否存在指定属性。
     *
     * @param key 属性名
     * @return true 表示存在；不在收集范围内时返回 false
     */
    public static boolean currentHasAttribute(String key) {
        LogCollectContext ctx = current();
        return ctx != null && ctx.hasAttribute(key);
    }

    // ---------- 只读信息静态便捷方法 ----------

    /**
     * 获取当前上下文 traceId。
     *
     * @return traceId；若不在收集范围内返回 null
     */
    public static String getCurrentTraceId() {
        LogCollectContext ctx = current();
        return ctx != null ? ctx.getTraceId() : null;
    }

    /**
     * 获取当前上下文累计收集条数。
     *
     * @return 当前累计收集条数；若不在收集范围内返回 0
     */
    public static int getCurrentCollectedCount() {
        LogCollectContext ctx = current();
        return ctx != null ? ctx.getTotalCollectedCount() : 0;
    }

    // ============ 框架内部方法（package-private）============

    /**
     * 压入一个上下文到当前线程栈顶。
     *
     * <p>仅供框架内部调用。
     *
     * @param ctx 上下文
     */
    static void push(LogCollectContext ctx) {
        invokeManager("push", new Class[]{LogCollectContext.class}, new Object[]{ctx});
    }

    /**
     * 从当前线程弹出一个上下文。
     *
     * <p>当栈空时主动调用 {@link ThreadLocal#remove()}，避免线程复用场景下内存泄漏。
     */
    static void pop() {
        invokeManager("pop", new Class[]{}, new Object[]{});
    }

    private static LogCollectContext invokeManagerCurrent() {
        Object value = invokeManager("current", new Class[]{}, new Object[]{});
        return value instanceof LogCollectContext ? (LogCollectContext) value : null;
    }

    private static Object invokeManager(String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Class<?> manager = Class.forName("com.logcollect.core.context.LogCollectContextManager");
            java.lang.reflect.Method method = manager.getMethod(methodName, paramTypes);
            return method.invoke(null, args);
        } catch (Exception ignored) {
            return null;
        } catch (Error e) {
            throw e;
        }
    }

    private static int toLevelInt(String level) {
        if (level == null) {
            return 0;
        }
        String v = level.trim().toUpperCase();
        if ("FATAL".equals(v)) return 5;
        if ("ERROR".equals(v)) return 4;
        if ("WARN".equals(v) || "WARNING".equals(v)) return 3;
        if ("INFO".equals(v)) return 2;
        if ("DEBUG".equals(v)) return 1;
        if ("TRACE".equals(v)) return 0;
        return 0;
    }
}
