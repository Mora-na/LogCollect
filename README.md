

# @LogCollect

<p align="center">
  <strong>全栈高可用业务日志聚合框架</strong>
</p>

<p align="center">
  零侵入 · 全异步覆盖 · 双日志框架适配 · 配置中心热更 · 分层降级熔断 · 纵深安全防护
</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-8%2B-brightgreen" alt="JDK 8+"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7%2B%20%7C%203.x-blue" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-green" alt="License"/>
  <img src="https://img.shields.io/badge/version-1.0.0--SNAPSHOT-orange" alt="Version"/>
</p>

---

## 目录

- [一、简介](#一简介)
- [二、核心特性](#二核心特性)
- [三、版本兼容矩阵](#三版本兼容矩阵)
- [四、快速开始](#四快速开始)
- [五、核心概念](#五核心概念)
- [六、双模式日志收集](#六双模式日志收集)
- [七、LogCollectHandler 接口参考](#七logcollecthandler-接口参考)
- [八、注解配置参考](#八注解配置参考)
- [九、全异步场景指南](#九全异步场景指南)
- [十、安全防护体系](#十安全防护体系)
- [十一、熔断降级机制](#十一熔断降级机制)
- [十二、配置中心集成](#十二配置中心集成)
- [十三、可观测性](#十三可观测性)
- [十四、Actuator 管理端点](#十四actuator-管理端点)
- [十五、项目结构](#十五项目结构)
- [十六、生产部署指南](#十六生产部署指南)
- [十七、常见问题](#十七常见问题)
- [十八、开发注意事项](#十八开发注意事项)
- [十九、框架定位与生态关系](#十九框架定位与生态关系)
- [二十、已知局限性](#二十已知局限性)
- [二十一、贡献指南](#二十一贡献指南)
- [二十二、代码规模统计（自动生成）](#二十二代码规模统计自动生成)

---

## 一、简介

### 这是什么

`@LogCollect` 是一个**轻量级业务日志聚合框架**，只需在方法上加一个注解，即可将该方法及其启动的**全部异步子线程**的日志精准聚合到业务数据库中，与全局日志天然隔离。

### 解决什么问题

在以下典型场景中，传统日志方案（文件日志 / ELK）难以满足需求：

| 场景 | 痛点 | @LogCollect 方案 |
|------|------|------------------|
| 每日对账定时任务 | 50个子线程的日志散落在文件中，按 traceId 手动 grep，效率低下 | 自动聚合到 `reconcile_log` 表的一条记录中，一个查询即可回溯全过程 |
| 支付接口审计 | 操作日志需与支付流水强绑定，文件日志无法做到 | 日志直接写入业务表，与支付记录关联查询 |
| 数据导入任务 | 并行导入 10 万条数据，需要知道哪条成功、哪条失败 | 每条导入的日志按任务聚合，失败可逐条追溯 |

### 一句话定位

> **业务日志聚合的最后一公里** —— 与 ELK / SkyWalking 互补，而非替代。

---

## 二、核心特性

```
┌──────────────────────────────────────────────────────────────────┐
│                        @LogCollect 核心特性                       │
│                                                                  │
│  🎯 精准聚合    仅捕获指定方法（含全部异步子线程）的日志           │
│  ⚡ 极致性能    异步非阻塞 + 双阈值缓冲 + Log4j2 无锁队列         │
│  🔄 双收集模式  单条入库 / 聚合刷写，注解一键切换                  │
│  🔗 上下文贯穿  LogCollectContext 贯穿 before→收集→after 全生命周期│
│  🔒 纵深安全    9 层防御：注入过滤 / 脱敏 / 防SQL注入 / 防泄漏     │
│  🛡️ 极端高可用  4 层降级 + 三状态熔断 + 指数退避 + 渐进恢复        │
│  🔌 双框架适配  Logback / Log4j2 自动识别，零配置切换              │
│  🧵 全异步覆盖  @Async / 线程池 / WebFlux / new Thread 全支持     │
│  ☁️ 配置热更    Nacos / Apollo / Spring Cloud Config 动态调整      │
│  📊 可观测性    Metrics + 健康检查 + Actuator 管理端点              │
│  📦 极简接入    一个注解 + 实现一个方法，两步完成接入               │
└──────────────────────────────────────────────────────────────────┘
```

---

## 三、版本兼容矩阵

| 依赖 | 最低版本 | 推荐版本 | 说明 |
|------|---------|---------|------|
| **JDK** | 1.8+ | 11+ / 17+ | 全版本编译测试通过 |
| **Spring Boot** | 2.7.x | 2.7.x / 3.x | 双版本自动适配 |
| **Spring Framework** | 5.3.x | 5.3.x / 6.x | - |
| **Context Propagation** | 1.0.6 | 1.0.6 (JDK 8) / 1.1.1 (JDK 11+) | BOM 按 JDK Profile 自动分流 |
| **Micrometer** | 1.10.13 | 1.10.x / 1.12.x | 可选，Metrics 依赖 |
| **Reactor** | 3.4.x（可选） | 3.5.3+（自动上下文传播） | WebFlux 场景需要 |

### Spring Boot 2.7 与 3.x 的差异

| 能力 | Spring Boot 2.7.x | Spring Boot 3.x |
|------|-------------------|-----------------|
| @Async 上下文传播 | 默认 `AsyncConfigurer` 自动补齐（自定义需确认 `TaskDecorator`） | 原生 Context Propagation 自动传播 |
| Spring 线程池传播 | 框架通过 `BeanPostProcessor` 自动包装 | 原生 Context Propagation 自动传播 |
| WebFlux 传播 | Reactor 3.5.3+：自动；3.4.x：框架手动 Hook | 原生自动传播 |
| 自动装配注册 | `META-INF/spring.factories` | `META-INF/spring/...AutoConfiguration.imports` |

> **对用户透明**：两种模式下，业务代码完全一致，无需任何修改。框架在启动时自动检测并适配。

---

## 四、快速开始

### 4.1 引入依赖

**Maven**

```xml
<!-- groupId 可使用 io.github.mora-na 或 org.dpdns.mora -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.mora-na</groupId>
            <!-- <groupId>org.dpdns.mora</groupId> -->
            <artifactId>logcollect-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- 核心 Starter（必须） -->
    <dependency>
        <groupId>io.github.mora-na</groupId>
        <!-- <groupId>org.dpdns.mora</groupId> -->
        <artifactId>logcollect-spring-boot-starter</artifactId>
    </dependency>
    
    <!-- Nacos 配置中心适配（可选） -->
    <dependency>
        <groupId>io.github.mora-na</groupId>
        <!-- <groupId>org.dpdns.mora</groupId> -->
        <artifactId>logcollect-config-nacos</artifactId>
    </dependency>

    <!-- 若项目使用 Log4j2（替代默认 Logback）需额外引入 -->
    <dependency>
        <groupId>io.github.mora-na</groupId>
        <!-- <groupId>org.dpdns.mora</groupId> -->
        <artifactId>logcollect-log4j2-adapter</artifactId>
    </dependency>
</dependencies>
```

**Gradle**

```groovy
dependencies {
    implementation platform('io.github.mora-na:logcollect-bom:1.0.0-SNAPSHOT')
    // 或 implementation platform('org.dpdns.mora:logcollect-bom:1.0.0-SNAPSHOT')
    implementation 'io.github.mora-na:logcollect-spring-boot-starter'
    // 或 implementation 'org.dpdns.mora:logcollect-spring-boot-starter'
    
    // 可选
    implementation 'io.github.mora-na:logcollect-config-nacos'
    // 或 implementation 'org.dpdns.mora:logcollect-config-nacos'
    // 使用 Log4j2 时额外引入
    implementation 'io.github.mora-na:logcollect-log4j2-adapter'
    // 或 implementation 'org.dpdns.mora:logcollect-log4j2-adapter'
}
```

### 4.2 实现 Handler（聚合模式，推荐）

```java
@Component
@RequiredArgsConstructor
public class TaskLogHandler implements LogCollectHandler {

    private final TaskLogService taskLogService;

    @Override
    public CollectMode preferredMode() {
        return CollectMode.AGGREGATE;
    }

    @Override
    public void before(LogCollectContext context) {
        // 初始化任务日志主记录，并把主键放入上下文（跨阶段复用）
        Long executionLogId = taskLogService.createStartLog(
                context.getTraceId(),
                context.getMethodSignature(),
                context.getStartTime());
        context.setBusinessId(executionLogId);
        context.setAttribute("operator", "system");
    }

    @Override
    public void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        // AGGREGATE 模式下批量回调：每次 flush 收到一段聚合日志
        Long executionLogId = context.getBusinessId(Long.class);
        taskLogService.appendAggregatedLog(
                executionLogId,
                aggregatedLog.getContent(),
                aggregatedLog.getEntryCount(),
                aggregatedLog.getMaxLevel(),
                aggregatedLog.isFinalFlush());
    }

    @Override
    public void after(LogCollectContext context) {
        // 收尾：写入终态和统计
        Long executionLogId = context.getBusinessId(Long.class);
        taskLogService.finish(
                executionLogId,
                context.hasError(),
                context.getElapsedMillis(),
                context.getTotalCollectedCount(),
                context.getTotalDiscardedCount(),
                context.getFlushCount(),
                context.hasError() ? context.getError().getMessage() : null);
    }
}
```

> **推荐**：继承框架提供的 `AbstractJdbcLogCollectHandler` 安全基类，自动使用参数化查询防止 SQL 注入。详见 [安全防护体系](#十安全防护体系)。

### 4.3 加注解

```java
@Service
public class ReconcileService {

    @LogCollect
    public void dailyReconcile() {
        log.info("开始对账，用户手机: 13812345678");
        // → 自动脱敏为: 开始对账，用户手机: 138****5678
        
        CompletableFuture.runAsync(() -> {
            log.info("子线程处理中...");
            // → 自动聚合到同一 traceId
        }, springManagedExecutor);
        
        log.info("对账完成");
    }
}
```

### 4.4 业务代码中直接访问当前上下文（静态访问器）

框架提供 `LogCollectContext` 静态访问器，业务代码可在任意层级直接读写当前收集上下文（类似 `MDC` / `SecurityContextHolder` 的使用方式）。

```java
@Service
public class ImportService {

    @LogCollect
    public void importData(String taskId, List<DataRecord> records) {
        LogCollectContext.setCurrentBusinessId(taskId);
        LogCollectContext.setCurrentAttribute("totalRecords", records.size());

        for (DataRecord record : records) {
            processRecord(record); // 无需层层传 taskId
        }
    }

    private void processRecord(DataRecord record) {
        String taskId = LogCollectContext.getCurrentBusinessId(String.class);
        Integer total = LogCollectContext.getCurrentAttribute("totalRecords", Integer.class);
        log.info("[{}] processing record={} / total={}", taskId, record.getId(), total);
    }
}
```

> 非 `@LogCollect` 范围内调用也安全：读操作返回 `null/0/false`，写操作静默忽略，不抛异常。

**完成。** 引入 starter 后，框架会自动注册日志采集 Appender，异步收集、缓冲聚合入库、日志净化、敏感脱敏、降级兜底、Metrics 上报全部自动开启。

---

## 五、核心概念

### 5.1 处理流水线

```
业务方法调用
    ↓
┌─────────────────────────────────────────────────────────────┐
│ 0. 配置解析                                                  │
│    四级合并：框架默认 ← 注解 ← 配置中心全局 ← 配置中心方法级   │
├─────────────────────────────────────────────────────────────┤
│ 1. AOP 前置                                                  │
│    生成 UUID traceId → 创建 LogCollectContext                │
│    → 栈式上下文 push → handler.before(context)               │
├─────────────────────────────────────────────────────────────┤
│ 2. 业务方法执行                                               │
│    每条日志 → Appender 拦截 → 检查 MDC traceId               │
│    ↓ 匹配                                                    │
│ 3. 收集过滤                                                  │
│    级别/Logger 过滤 → handler.shouldCollect() 自定义过滤      │
│    ↓ 通过                                                    │
│ 4. 长度守卫                                                   │
│    StringLengthGuard：content / throwable 分别限长            │
│    防止超长消息或堆栈导致缓冲区膨胀与 OOM                     │
│    ↓                                                         │
│ 5. 安全流水线（唯一入口）                                      │
│    SecurityPipeline：sanitize + mask 单入口处理               │
│    同时净化 content / throwable / thread / logger / level / MDC 值 │
│    ↓                                                         │
│ 6. 模式分发                                                   │
│    ├─ SINGLE:    构建 LogEntry → SingleModeBuffer             │
│    └─ AGGREGATE: formatLogLine → AggregateModeBuffer          │
│    ↓                                                         │
│ 7. 缓冲层（双阈值：条数 + 内存 + 溢出策略）                    │
│    BoundedBufferPolicy: FLUSH_EARLY / DROP_OLDEST / DROP_NEWEST│
│    ↓ 达到阈值或方法结束                                       │
│ 8. 熔断层（三状态：CLOSED → OPEN → HALF_OPEN）               │
│    ↓ 允许写入                                                │
│ 9. 模式分发调用                                               │
│    ├─ SINGLE:    循环调用 handler.appendLog(context, entry)   │
│    └─ AGGREGATE: 一次调用 handler.flushAggregatedLog(ctx,agg) │
│    ↓ 失败（ResilientFlusher 重试 + 本地兜底）                 │
│10. 降级层（流量削峰 → 熔断 → 兜底存储 → 终极丢弃）            │
├─────────────────────────────────────────────────────────────┤
│11. AOP 后置（finally）                                       │
│    设置 context.returnValue / context.error                   │
│    → flush 剩余缓冲 → handler.after(context)                 │
│    → 栈式上下文 pop                                           │
│    默认异常隔离；若 blockWhenDegradeFail=true 且兜底失败可显式抛出异常│
└─────────────────────────────────────────────────────────────┘
```

#### 5.1.1 数据流（事件 → 安全日志 → 双模式缓冲 → Handler）

```
                         ILoggingEvent (Logback) / LogEvent (Log4j2)
                                         │
                         ┌───────────────┴───────────────────┐
                         │        Appender 层（适配器）        │
                         │                                    │
                         │  提取字段 → 构建原始 LogEntry       │
                         │  ★ 立即释放原始事件引用             │
                         │  ★ 异常堆栈提取为 String            │
                         └───────────────┬───────────────────┘
                                         │ LogEntry (原始)
                         ┌───────────────┴───────────────────┐
                         │        安全流水线（Core 层）        │
                         │                                    │
                         │  ① sanitize(content)   → 严格净化  │
                         │  ② sanitizeThrowable() → 宽松净化  │
                         │  ③ mask(content)        → 脱敏     │
                         │  ④ mask(throwable)      → 脱敏     │
                         │  ⑤ 构建安全 LogEntry               │
                         └───────────────┬───────────────────┘
                                         │ LogEntry (安全)
                    ┌────────────────────┴──────────────────────┐
                    │                                           │
            ┌───────┴────────┐                      ┌───────────┴──────────┐
            │  SINGLE 缓冲区  │                      │  AGGREGATE 缓冲区    │
            │                │                      │                      │
            │  存储完整       │                      │ handler.formatLogLine│
            │  LogEntry 对象  │                      │  ↓                   │
            │                │                      │ 存 formattedLine     │
            │                │                      │ (LogSegment)         │
            │                │                      │ ★ LogEntry 可被 GC   │
            └───────┬────────┘                      └───────────┬──────────┘
                    │ flush                                     │ flush
                    ↓                                           ↓
        handler.appendLog(ctx, entry)            StringBuilder 拼接 + separator
        ├ entry.getContent()        结构化存储      → AggregatedLog.content
        ├ entry.getThrowableString() 异常堆栈      handler.flushAggregatedLog(ctx, agg)
        └ handler.formatLogLine(entry) 按需格式化
```

### 5.2 LogCollectContext 上下文模型

`LogCollectContext` 是贯穿日志收集全生命周期的上下文对象，在 `before()` → 日志收集 → `after()` 各阶段共享同一个实例。

```
LogCollectContext
├── 不可变字段（框架创建时设置）
│   ├── traceId            本次收集的唯一追踪 ID（UUID）
│   ├── methodSignature    被拦截方法的全限定签名
│   ├── className          类名（短名）
│   ├── methodName         方法名
│   ├── methodArgs         方法入参快照
│   ├── startTime          方法开始时间
│   ├── startTimeMillis    开始时间戳（用于计算耗时）
│   ├── collectMode        本次使用的收集模式（SINGLE / AGGREGATE）
│   └── handlerClass       Handler 实例的 Class 类型
│
├── 执行状态（框架在 AOP finally 中设置）
│   ├── returnValue        方法返回值（正常结束时）
│   └── error              方法抛出的异常（异常结束时）
│
├── 收集统计（框架维护，用户只读）
│   ├── totalCollectedCount    本次共收集的日志条数
│   ├── totalDiscardedCount    本次共丢弃的日志条数
│   ├── totalCollectedBytes    本次共收集的日志字节数
│   └── flushCount             已执行的 flush 次数
│
└── 用户自定义状态
    ├── businessId         业务唯一标识
    └── attributes         ConcurrentHashMap，支持跨阶段传递任意数据
```

**便捷方法**：

| 方法 | 说明 |
|------|------|
| `hasError()` | 方法是否异常结束 |
| `getElapsedMillis()` | 方法已执行的毫秒数 |
| `getBusinessId(Class<T>)` | 类型安全地获取 businessId |
| `setAttribute(key, value)` | 设置自定义属性（跨阶段传递） |
| `getAttribute(key, Class<T>)` | 类型安全地获取自定义属性 |
| `hasAttribute(key)` | 判断属性是否存在 |

**线程安全设计**：

| 字段类别 | 策略 | 说明 |
|----------|------|------|
| 不可变字段 | `final` | 创建后不可修改 |
| `returnValue` / `error` / `businessId` | `volatile` | 单线程写入，多线程可读 |
| 统计计数器 | `AtomicInteger` / `AtomicLong` | 多线程并发递增 |
| `attributes` | `ConcurrentHashMap` | 多线程并发读写 |

### 5.3 上下文传播模型

```
主线程
│  @LogCollect 方法入口
│  ├── push(LogCollectContext) → ThreadLocal + MDC
│  │
│  ├── log.info("主线程日志")  ────────────────────── ✅ 自动拦截
│  │
│  ├── @Async 子线程  ─── TaskDecorator 自动传播 ──── ✅ 自动拦截
│  │
│  ├── CompletableFuture + Spring池  ──────────────── ✅ 自动拦截
│  │
│  ├── WebFlux Mono/Flux  ── Reactor Hook 自动传播 ── ✅ 自动拦截
│  │
│  ├── new Thread(wrapRunnable(...))  ── 手动包装 ─── ✅ 手动一行
│  │
│  ├── 嵌套 @LogCollect ── push(ctx-B) / pop(ctx-B) ─ ✅ 栈式隔离
│  │
│  └── finally: pop(ctx-A) → 栈空 → ThreadLocal.remove()
```

### 5.4 四级配置优先级

```
优先级从高到低：

① 配置中心 - 方法级配置    logcollect.methods.{类名_方法名}.level=ERROR
   ↓ 覆盖
② 配置中心 - 全局配置      logcollect.global.level=WARN
   ↓ 覆盖
③ @LogCollect 注解配置      @LogCollect(minLevel = "INFO")
   ↓ 覆盖
④ 框架默认配置              INFO（硬编码在框架中）

合并规则：每个参数独立合并，高优先级仅覆盖其显式设置的参数。

Handler 解析优化：
- `@LogCollect` 方法的 Handler 解析结果按 `Method` 缓存，避免每次调用重复扫描容器
- 配置中心刷新后缓存自动失效，下次调用按最新配置重新解析
```

### 5.5 日志框架接入（自动 Appender 注册）

为实现零配置接入，框架在启动时会自动将采集 Appender 挂到 ROOT Logger：

- Logback：`com.logcollect.logback.LogCollectLogbackAppender`
- Log4j2：`com.logcollect.log4j2.LogCollectLog4j2Appender`

默认配置如下：

```yaml
logcollect:
  logging:
    auto-register-appender: true
    appender-name: LOG_COLLECT
```

说明：

- `auto-register-appender=true`（默认）：框架自动注册，无需手工改 `logback-spring.xml` / `log4j2.xml`。
- `appender-name`：自动注册时使用的 Appender 名称，默认 `LOG_COLLECT`。
- 如果你关闭自动注册（`false`），需要自行在日志框架配置中挂载对应 Appender 到 ROOT。
- 框架启动时会通过 `ConsolePatternDetector` SPI 探测控制台 pattern，并经 `PatternCleaner + PatternValidator` 清理校验后写入 `LogLineDefaults`。
- `ConsolePatternDetector` 使用 `getOrder()` 排序而非继承 `org.springframework.core.Ordered`，目的是避免 SPI 接口与 Spring 强耦合；功能上无差异，`ConsolePatternInitializer` 会按 `getOrder()` 升序选择第一个可用探测器。

### 5.6 LogCollectContext 静态访问器

设计目标：

- 业务代码可直接获取当前 `@LogCollect` 上下文（ThreadLocal 栈顶），无需层层传参。
- 与 `MDC.put(...)`、`SecurityContextHolder.getContext()`、`RequestContextHolder.getRequestAttributes()` 类似的调用体验。
- 在非收集范围内保持“静默安全”，不影响普通业务方法。

常用静态方法：

| 方法 | 返回值 | 非收集范围内行为 | 典型用途 |
|------|--------|------------------|----------|
| `current()` | `LogCollectContext` / `null` | `null` | 获取完整上下文 |
| `isActive()` | `boolean` | `false` | 判断是否在收集范围内 |
| `getCurrentTraceId()` | `String` / `null` | `null` | 取 traceId |
| `getCurrentCollectedCount()` | `int` | `0` | 取当前收集条数 |
| `setCurrentBusinessId(Object)` | `void` | 静默忽略 | 业务方法中设置 businessId |
| `getCurrentBusinessId(Class<T>)` | `T` / `null` | `null` | 读取 businessId |
| `setCurrentAttribute(String,Object)` | `void` | 静默忽略 | 写自定义上下文参数 |
| `getCurrentAttribute(String)` | `Object` / `null` | `null` | 读自定义参数 |
| `getCurrentAttribute(String,Class<T>)` | `T` / `null` | `null` | 类型安全读取参数 |
| `currentHasAttribute(String)` | `boolean` | `false` | 判断参数是否存在 |

线程与内存安全说明：

- 主线程与子线程访问通过上下文传播机制协同，属性容器使用 `ConcurrentHashMap`，并发读写安全。
- 嵌套 `@LogCollect` 场景下按栈顶上下文生效，天然栈式隔离。
- 生命周期结束后上下文会出栈并清理 `ThreadLocal`，避免泄漏。
- 建议不要将超大对象放入 `attribute`（生命周期随整次方法执行）。

---

## 六、双模式日志收集

框架提供两种日志收集模式，由注解 `collectMode` 参数决定（二选一），默认使用效率更高的聚合模式。

### 6.1 模式总览

```
┌────────────────────────────────────────────────────────────────────────────┐
│  模式1 SINGLE（单条缓冲模式）                                              │
│  ┌──────┐  ┌──────┐  ┌──────┐        ┌─────────────────────────┐          │
│  │Log 1 │→ │Log 2 │→ │Log N │ ────→  │ 批量循环调用 appendLog  │          │
│  └──────┘  └──────┘  └──────┘        │ appendLog(ctx, entry1)  │          │
│   过滤+脱敏后逐条放入缓冲区            │ appendLog(ctx, entry2)  │          │
│   达到阈值 / 方法结束 → flush          │ ...                     │          │
│   缓冲区: ConcurrentLinkedQueue        │ appendLog(ctx, entryN)  │          │
│           <LogEntry>                   └─────────────────────────┘          │
│                                                                            │
│  模式2 AGGREGATE（聚合刷写模式）⭐ 默认                                     │
│  ┌──────┐  ┌──────┐  ┌──────┐        ┌──────────────────────────────┐     │
│  │Log 1 │→ │Log 2 │→ │Log N │ ────→  │ 一次调用 flushAggregatedLog │     │
│  └──────┘  └──────┘  └──────┘        │ 参数: 一整个大日志体          │     │
│   过滤+脱敏后格式化为一行              │ "[10:00] INFO 日志1\n        │     │
│   追加到聚合缓冲区                     │  [10:01] WARN 日志2\n        │     │
│   达到阈值 / 方法结束 → flush          │  [10:02] ERROR 日志3"        │     │
│   缓冲区: ConcurrentLinkedQueue        └──────────────────────────────┘     │
│           <LogSegment>                                                     │
│   flush时: StringBuilder 拼接                                              │
└────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 两种模式对比

| 对比维度 | 模式1 SINGLE | 模式2 AGGREGATE（默认） |
|----------|-------------|----------------------|
| Handler 调用 | N 次 `appendLog()` | 1~几次 `flushAggregatedLog()` |
| 典型 DB 操作 | N 次 INSERT | 1 次 INSERT / UPDATE |
| 对象创建 | 每条创建 `LogEntry` 对象 | 仅存储格式化后的 `String`，flush 时构建 `AggregatedLog` |
| 内存效率 | 每条 ~128 字节额外开销 | 每条 ~16 字节额外开销 |
| GC 压力 | N 个对象 | 极少对象，flush 时 `StringBuilder` 拼接 |
| 适用场景 | 逐条入库（明细表）、逐条重试 | 大批量日志聚合到单条记录（推荐） |

> **结论**：AGGREGATE 在对象开销、方法调用次数、DB 写入次数上全面优于 SINGLE，因此设为默认模式。

### 6.3 使用方式

#### 聚合模式（默认，推荐）

```java
// 默认即为聚合模式，无需显式指定
@LogCollect
public void dailyReconcile() { ... }

// 显式指定
@LogCollect(collectMode = CollectMode.AGGREGATE)
public void dailyReconcile() { ... }
```

```java
@Component
public class ReconcileLogHandler implements LogCollectHandler {

    @Autowired
    private ReconcileLogMapper mapper;

    @Override
    public void before(LogCollectContext context) {
        ReconcileLog log = new ReconcileLog();
        log.setTraceId(context.getTraceId());
        log.setTaskName(context.getMethodName());
        log.setStatus("RUNNING");
        log.setStartTime(context.getStartTime());
        log.setLogContent("");
        mapper.insert(log);
        
        context.setBusinessId(log.getId());
        context.setAttribute("batchDate", LocalDate.now().toString());
    }

    @Override
    public void flushAggregatedLog(LogCollectContext context,
                                   AggregatedLog aggregatedLog) {
        // 一次 UPDATE 追加一整块日志到 content 字段
        Long logId = context.getBusinessId(Long.class);
        mapper.appendContent(logId, aggregatedLog.getContent());
        
        // 可利用聚合体的元信息
        if ("ERROR".equals(aggregatedLog.getMaxLevel())) {
            mapper.markHasError(logId);
        }
    }

    @Override
    public void after(LogCollectContext context) {
        Long logId = context.getBusinessId(Long.class);
        ReconcileLog log = new ReconcileLog();
        log.setId(logId);
        log.setStatus(context.hasError() ? "FAILED" : "SUCCESS");
        log.setEndTime(LocalDateTime.now());
        log.setElapsedMs(context.getElapsedMillis());
        log.setCollectedCount(context.getTotalCollectedCount());
        log.setFlushCount(context.getFlushCount());
        log.setBatchDate(context.getAttribute("batchDate", String.class));
        log.setErrorMsg(context.hasError() ? context.getError().getMessage() : null);
        mapper.updateById(log);
    }
}
```

#### 单条模式（需要逐条入明细表时使用）

```java
@LogCollect(collectMode = CollectMode.SINGLE, maxBufferSize = 50)
public void importData(List<DataRecord> records) { ... }
```

```java
@Component
public class ImportLogHandler implements LogCollectHandler {

    @Autowired
    private ImportLogMapper logMapper;
    @Autowired
    private ImportLogDetailMapper detailMapper;

    @Override
    public CollectMode preferredMode() {
        return CollectMode.SINGLE;
    }

    @Override
    public void before(LogCollectContext context) {
        ImportLog log = new ImportLog();
        log.setTraceId(context.getTraceId());
        log.setStatus("RUNNING");
        logMapper.insert(log);
        context.setBusinessId(log.getId());
    }

    @Override
    public void appendLog(LogCollectContext context, LogEntry entry) {
        // 每条日志插入明细表（框架从缓冲区批量循环调用）
        ImportLogDetail detail = new ImportLogDetail();
        detail.setLogId(context.getBusinessId(Long.class));
        detail.setContent(entry.getContent());   // 已过净化 + 脱敏
        detail.setLevel(entry.getLevel());
        detail.setLogTime(entry.getTime());
        detail.setThreadName(entry.getThreadName());
        detailMapper.insert(detail);
    }

    @Override
    public void after(LogCollectContext context) {
        ImportLog log = new ImportLog();
        log.setId(context.getBusinessId(Long.class));
        log.setStatus(context.hasError() ? "FAILED" : "SUCCESS");
        log.setTotalLogs(context.getTotalCollectedCount());
        logMapper.updateById(log);
    }
}
```

### 6.4 模式自动选择逻辑

当注解配置为 `CollectMode.AUTO`（默认值）时：

```
@LogCollect(collectMode = AUTO)
         │
         ▼
handler.preferredMode() 返回值?
  ├── SINGLE    → 使用模式1
  ├── AGGREGATE → 使用模式2
  └── AUTO      → 使用模式2（框架默认，效率更高）
```

优先级：**注解显式指定 > Handler.preferredMode() > 框架默认(AGGREGATE)**

### 6.5 缓冲区内部设计

#### 单条缓冲区 SingleModeBuffer

```
ConcurrentLinkedQueue<LogEntry>
┌─────────┬─────────┬─────────┬─────────┬─────────┐
│ LogEntry│ LogEntry│ LogEntry│ LogEntry│ LogEntry│  ← 每条一个对象
│ content │ content │ content │ content │ content │
│ level   │ level   │ level   │ level   │ level   │
│ timestamp│timestamp│timestamp│timestamp│timestamp│
│threadNm │threadNm │threadNm │threadNm │threadNm │
│loggerNm │loggerNm │loggerNm │loggerNm │loggerNm │
│throwable│throwable│throwable│throwable│throwable│
│mdcMap   │mdcMap   │mdcMap   │mdcMap   │mdcMap   │
└─────────┴─────────┴─────────┴─────────┴─────────┘

flush → drain 全部 entry → 循环调用 handler.appendLog(context, entry)
每次 flush 产生 N 次 handler 调用
```

- **数据结构**：`ConcurrentLinkedQueue<LogEntry>`（无锁队列，多线程并发 offer 无争用）
- **内存跟踪**：`AtomicInteger count` + `AtomicLong bytes` + `BoundedBufferPolicy`
- **flush 触发**：`count >= maxBufferSize` 或 `bytes >= maxBufferBytes`
- **溢出策略**：`FLUSH_EARLY`（默认）/ `DROP_OLDEST` / `DROP_NEWEST`
- **flush 动作**：drain 全部 entry → 循环调用 `handler.appendLog(context, entry)`（失败走 `ResilientFlusher` 重试和本地兜底）

#### 聚合缓冲区 AggregateModeBuffer

```
ConcurrentLinkedQueue<LogSegment>（仅存格式化后的字符串）
┌───────────────┬───────────────┬───────────────┐
│ "[10:00] INFO  │ "[10:01] WARN │ "[10:02] ERROR│
│  日志内容1"    │  日志内容2"    │  日志内容3"    │
└───────────────┴───────────────┴───────────────┘

flush → StringBuilder 拼接 → 构建 AggregatedLog（一整个大字符串）
     → 一次调用 handler.flushAggregatedLog(context, aggregatedLog)
每次 flush 产生 1 次 handler 调用
```

- **数据结构**：`ConcurrentLinkedQueue<LogSegment>`（`LogSegment` 字段：`formattedLine + level + timestamp + estimatedBytes + patternVersion`）
- **内存跟踪**：`AtomicInteger count` + `AtomicLong bytes` + `BoundedBufferPolicy`
- **flush 触发**：`count >= maxBufferSize` 或 `bytes >= maxBufferBytes`
- **溢出策略**：`FLUSH_EARLY`（默认）/ `DROP_OLDEST` / `DROP_NEWEST`
- **Pattern 热更一致性**：每个 `LogSegment` 记录 `patternVersion`，flush 时按版本分批，保证同批次 pattern 一致
- **flush 动作**：drain 全部片段 → `StringBuilder` 拼接 → 构建 `AggregatedLog` → 一次调用 `handler.flushAggregatedLog()`（失败走 `ResilientFlusher`）

> **为何不直接用 StringBuilder**：`StringBuilder` 不是线程安全的，多线程并发 append 会产生数据错乱。使用 `ConcurrentLinkedQueue<LogSegment>` 存储片段 + flush 时聚合，兼顾线程安全与内存效率。

### 6.6 内存安全防护

两种模式共享同一套六层内存保护机制：

```
每条日志到达缓冲区时的内存保护：

第一层：级别/Logger 前置过滤
  低于配置级别的日志直接丢弃，零内存占用
  命中 exclude-loggers 前缀直接跳过

第二层：shouldCollect() 自定义过滤
  用户可按业务逻辑过滤，进一步减少入队量

第三层：长度守卫
  StringLengthGuard 限制 content / throwable 最大长度
  默认 32KB / 64KB，防止超长消息入队前撑爆堆内存

第四层：单缓冲区阈值（per-method）
  count >= maxBufferSize → 触发 flush
  bytes >= maxBufferBytes → 触发 flush
  触发 BoundedBufferPolicy：
    FLUSH_EARLY / DROP_OLDEST / DROP_NEWEST

第五层：全局内存阈值（cross-method）
  GlobalBufferMemoryManager.tryAllocate() 失败 → 触发降级
  降级策略：丢弃 DEBUG/INFO，仅保留 WARN/ERROR
  Metrics: reason=global_memory_limit

第六层：缓冲区关闭保护
  方法结束后 closed=true，迟到的日志被拒绝
  防止已结束方法的缓冲区被遗留异步线程继续填充
```

**全局内存管控器**：控制所有活跃 `@LogCollect` 方法的缓冲区总内存占用，防止多个并发收集任务同时积压导致 OOM。配置项：`logcollect.global.buffer.total-max-bytes`（默认 `100MB`）。

### 6.7 缓冲区线程安全总结

| 组件 | 策略 | 无锁？ |
|------|------|--------|
| 日志入队 | `ConcurrentLinkedQueue.offer()` | ✅ |
| 条数统计 | `AtomicInteger.incrementAndGet()` | ✅ CAS |
| 字节统计 | `AtomicLong.addAndGet()` | ✅ CAS |
| 溢出策略计数 | `BoundedBufferPolicy`(`AtomicLong/AtomicInteger`) | ✅ CAS |
| flush 互斥 | `AtomicBoolean.compareAndSet()` | ✅ CAS |
| 关闭标记 | `AtomicBoolean.set(true)` | ✅ |
| 全局内存 | `AtomicLong` CAS | ✅ CAS |
| 异步 flush 调度 | `ThreadPoolExecutor + LinkedBlockingQueue` | ❌ Lock-based |

> 日志收集热路径无锁（入队、计数、阈值判断均基于 CAS）。  
> 异步 flush 调度线程池使用 `LinkedBlockingQueue`，属于锁实现，但不在业务线程热路径上。

### 6.8 调用时序示例

#### 模式1 SINGLE 时序（maxBufferSize=3）

```
日志1 → 过滤✅ → 长度守卫 → SecurityPipeline(净化+脱敏) → LogEntry → 入缓冲区  count=1
日志2 → 过滤✅ → 长度守卫 → SecurityPipeline(净化+脱敏) → LogEntry → 入缓冲区  count=2
日志3 → 过滤✅ → 长度守卫 → SecurityPipeline(净化+脱敏) → LogEntry → 入缓冲区  count=3 → 达到阈值!
  └─ flush:
     ├─ handler.appendLog(ctx, entry1)  ← INSERT INTO detail ...
     ├─ handler.appendLog(ctx, entry2)
     └─ handler.appendLog(ctx, entry3)
日志4 → 入缓冲区  count=1
方法结束 → final flush:
  └─ handler.appendLog(ctx, entry4)
```

#### 模式2 AGGREGATE 时序（maxBufferSize=3）

```
日志1 → 过滤✅ → 长度守卫 → SecurityPipeline → formatLogLine → "[10:00] INFO 日志1" → 入缓冲区  count=1
日志2 → 过滤✅ → 长度守卫 → SecurityPipeline → formatLogLine → "[10:01] INFO 日志2" → 入缓冲区  count=2
日志3 → 过滤✅ → 长度守卫 → SecurityPipeline → formatLogLine → "[10:02] WARN 日志3" → 入缓冲区  count=3 → 达到阈值!
  └─ flush:
     ├─ StringBuilder 拼接三条
     └─ handler.flushAggregatedLog(ctx, AggregatedLog{
          content = "[10:00] INFO 日志1\n[10:01] INFO 日志2\n[10:02] WARN 日志3",
          entryCount = 3,
          maxLevel = "WARN",
          finalFlush = false    // 中途刷写
        })                       ← 一次 UPDATE ...
日志4 → formatLogLine → 入缓冲区  count=1
方法结束 → final flush:
  └─ handler.flushAggregatedLog(ctx, AggregatedLog{
       content = "[10:03] INFO 日志4",
       entryCount = 1,
       finalFlush = true        // 最终刷写
     })
```

### 6.9 模型类

#### LogEntry（两种模式均使用）

```java
public final class LogEntry {
    private final String traceId;
    private final String content;         // 已经过净化 + 脱敏处理
    private final String level;
    private final long timestamp;         // 唯一时间源（epoch milli）
    private final String threadName;
    private final String loggerName;
    private final String throwableString; // 异常堆栈（可为空）
    private final Map<String, String> mdcContext;

    public LocalDateTime getTime() { ... }   // 按 timestamp 延迟计算
    public boolean hasThrowable() { ... }
    public long estimateBytes() { ... }      // 112 + 字符串 + mdc map 估算

    public static Builder builder() { ... }
}
```

与旧版对比：

| 变化 | 说明 |
|------|------|
| 去掉 `formattedLine` | 格式化在需要时执行（AGGREGATE 入缓冲时 / SINGLE 按需调用） |
| 新增 `throwableString` | 异常堆栈不再丢失 |
| 新增 `timestamp` | 高效排序/比较；`getTime()` 按需派生 |
| 新增 `mdcContext` | 支持 `%X{key}` 输出上下文 |
| `estimateBytes()` 修正 | 统一按对象开销 + 字符串 + MDC map 保守估算 |
| 构造器 `private + Builder` | 后续扩展字段不破坏 API |

#### AggregatedLog（仅模式2 使用）

```java
public class AggregatedLog {
    /** 本次 flush 的唯一标识（UUID），用于幂等处理 */
    private final String flushId;

    /** 聚合后的完整日志体（一整个大字符串，不是列表） */
    private final String content;
    
    /** 本次聚合体包含的日志条数 */
    private final int entryCount;
    
    /** 本次聚合体的总字节数 */
    private final long totalBytes;
    
    /** 本次聚合体中最高严重级别 */
    private final String maxLevel;
    
    /** 第一条日志的时间 */
    private final LocalDateTime firstLogTime;
    
    /** 最后一条日志的时间 */
    private final LocalDateTime lastLogTime;
    
    /**
     * 是否为最终刷写（方法结束触发）。
     * true  = 方法已结束，这是最后一批
     * false = 中途阈值触发，后续可能还有
     */
    private final boolean finalFlush;
}
```

---

## 七、LogCollectHandler 接口参考

### 7.1 完整接口定义

```java
public interface LogCollectHandler {

    // ==================== 生命周期方法 ====================

    /**
     * 方法执行前回调。
     * 典型用法：插入初始记录、context.setBusinessId()、context.setAttribute()
     */
    default void before(LogCollectContext context) {}

    /**
     * 方法执行后回调（flush 剩余缓冲之后调用）。
     * 此时 context 包含完整信息：returnValue/error/统计/属性等。
     */
    default void after(LogCollectContext context) {}

    // ==================== 模式1：单条缓冲模式 ====================

    /**
     * 【模式1】追加单条日志。
     * 框架从缓冲区 drain 后循环调用。content 已过净化 + 脱敏。
     */
    default void appendLog(LogCollectContext context, LogEntry entry) {
        context.incrementDiscardedCount();
        if (context.getTotalDiscardedCount() == 1) {
            System.err.println("[LogCollect-WARN] appendLog not implemented in "
                + getClass().getSimpleName()
                + ", entries are being dropped for traceId=" + context.getTraceId()
                + ". Implement appendLog or switch to AGGREGATE mode.");
        }
    }

    // ==================== 模式2：聚合刷写模式 ====================

    /**
     * 【模式2】刷写聚合日志体。
     * 所有日志格式化、拼接为一个大字符串后一次调用。
     * 如日志量超阈值，可能被调用多次，通过 aggregatedLog.isFinalFlush() 判断。
     */
    default void flushAggregatedLog(LogCollectContext context,
                                    AggregatedLog aggregatedLog) {
        throw new UnsupportedOperationException(
            "AGGREGATE 模式需实现 flushAggregatedLog。如需单条模式，请实现 appendLog");
    }

    // ==================== 格式化与定制 ====================

    /**
     * 【模式2专用】日志行格式 pattern。
     * 支持：
     * %d %p/%level %t/%thread %c/%logger %C/%loggerFull %m/%msg
     * %ex/%throwable/%wEx %n %X{key}
     * 默认值由控制台 pattern 自动探测，失败时回退内置 fallback。
     */
    default String logLinePattern() {
        return LogLineDefaults.getEffectivePattern();
    }

    /**
     * 【模式2专用】按 pattern 格式化单条日志。
     * 默认实现使用 LogLinePatternParser（含编译缓存）。
     */
    default String formatLogLine(LogEntry entry) {
        return LogLinePatternParser.format(entry, logLinePattern());
    }

    /**
     * 【模式2专用】聚合日志体的行分隔符。默认 "\n"。
     */
    default String aggregatedLogSeparator() {
        return "\n";
    }

    // ==================== 收集过滤 ====================

    /**
     * 自定义日志收集过滤器。
     * 在级别过滤之后、安全流水线之前调用。返回 false 跳过该条日志。
     * messageSummary 为安全摘要（基础清理后，最长 256 字符）。
     */
    default boolean shouldCollect(LogCollectContext context,
                                  String level, String messageSummary) {
        return true;
    }

    // ==================== 模式偏好 ====================

    /**
     * 声明本 Handler 偏好的收集模式。
     * 当注解 collectMode=AUTO 时，框架据此决策。返回 AUTO 则使用框架默认(AGGREGATE)。
     */
    default CollectMode preferredMode() {
        return CollectMode.AUTO;
    }

    // ==================== 降级与错误处理 ====================

    /**
     * 降级事件回调。日志写入失败触发降级时调用。
     */
    default void onDegrade(LogCollectContext context, DegradeEvent event) {}

    /**
     * 写入异常处理。appendLog 或 flushAggregatedLog 抛异常后调用。
     */
    default void onError(LogCollectContext context, Throwable error, String phase) {}
}
```

### 7.2 方法速查表

| 方法 | 类型 | 默认行为 | 职责 | 适用模式 |
|------|------|---------|------|---------|
| `before(context)` | 生命周期 | 空实现 | 初始化业务记录、设置 businessId 和 attributes | 两种 |
| `after(context)` | 生命周期 | 空实现 | 更新终态（成功/失败/耗时/统计） | 两种 |
| `appendLog(context, entry)` | 收集入口 | 首次告警并丢弃 | 逐条日志写入 | SINGLE |
| `flushAggregatedLog(context, aggLog)` | 收集入口 | 抛异常 | 聚合日志体一次性写入 | AGGREGATE |
| `logLinePattern()` | 格式化 | `LogLineDefaults.getEffectivePattern()` | 定义默认 pattern | AGGREGATE |
| `formatLogLine(entry)` | 格式化 | `LogLinePatternParser.format(...)` | 定义聚合体每行格式 | AGGREGATE |
| `aggregatedLogSeparator()` | 格式化 | `"\n"` | 聚合体行分隔符 | AGGREGATE |
| `shouldCollect(context, level, messageSummary)` | 过滤 | `return true` | 基于安全摘要（基础清理后、最长 256 字符）做业务过滤 | 两种 |
| `preferredMode()` | 模式选择 | `return AUTO` | 声明偏好模式 | 两种 |
| `onDegrade(context, event)` | 降级 | 空实现 | 降级通知（可发告警） | 两种 |
| `onError(context, error, phase)` | 错误处理 | 空实现 | 写入异常通知 | 两种 |

> **幂等建议**：`ResilientFlusher` 失败会重试。  
> `flushAggregatedLog` 入参 `AggregatedLog` 含唯一 `flushId`，建议落库前做幂等检查（如唯一索引/UPSERT）；`appendLog` 也建议按业务键实现幂等。

### 7.3 方法协作流程

```
框架创建 LogCollectContext
    │
    ▼
handler.before(context)                              ← 用户: 初始化
    │  context.setBusinessId(id)
    │  context.setAttribute("key", value)
    │
    ▼
┌─ 业务方法执行期间 ──────────────────────────────────────────────┐
│  每条日志：                                                     │
│    ① 级别/Logger过滤 → ② handler.shouldCollect()?               │
│    ③ 长度守卫 → ④ SecurityPipeline(净化+脱敏)                   │
│    ⑤ 进入缓冲区:                                                │
│       SINGLE:    LogEntry → SingleModeBuffer                    │
│       AGGREGATE: handler.formatLogLine(entry) → AggregateBuffer │
│                                                                  │
│  达到阈值时 flush:                                               │
│    SINGLE:    循环 handler.appendLog(context, entry)             │
│    AGGREGATE: 一次 handler.flushAggregatedLog(context, aggLog)   │
│                                                                  │
│  写入失败: 熔断降级 → handler.onDegrade() → handler.onError()    │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
框架设置 context.returnValue / context.error
    │
    ▼
最终 flush 缓冲区剩余日志
    │
    ▼
handler.after(context)                               ← 用户: 收尾
    │  context.getBusinessId() / context.hasError()
    │  context.getTotalCollectedCount() / context.getElapsedMillis()
    │  context.getAttribute("key")
    │
    ▼
框架 pop 上下文 → ThreadLocal 清理
```

### 7.4 最简实现

**如果只需要聚合模式**，可只实现 `flushAggregatedLog`（`AUTO` 默认解析为 `AGGREGATE`）：

```java
@Component
public class SimpleLogHandler implements LogCollectHandler {

    @Autowired
    private LogMapper logMapper;

    @Override
    public void flushAggregatedLog(LogCollectContext context,
                                   AggregatedLog aggregatedLog) {
        logMapper.insertOrAppend(
            context.getTraceId(),
            aggregatedLog.getContent()
        );
    }
}
```

> 如调用方显式使用 `collectMode=SINGLE`，未实现 `appendLog` 时框架会打印告警并丢弃该条日志，不再抛 `UnsupportedOperationException`。

---

## 八、注解配置参考

### 8.1 完整参数表

#### 基础配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `handler` | `Class` | 自动匹配 | 业务日志处理实现类。选择优先级：注解显式指定 > 容器唯一实现 > `@Primary`。无 Handler 时回退 `NoopLogCollectHandler` 并打印启动告警；多个且无 `@Primary` 且存在 `handler=AUTO` 的 `@LogCollect` 方法时启动失败 |
| `async` | `boolean` | `true` | 是否异步收集日志。异步时业务线程仅写入队列即返回 |
| `minLevel` | `String` | `""`（空） | 注解维度最低采集级别；空表示“未显式设置”，由框架默认 `INFO` 或配置中心覆盖 |
| `excludeLoggers` | `String[]` | `{}` | 按 logger 名前缀排除（如 `org.springframework.`） |
| `collectMode` | `CollectMode` | `AUTO` | 日志收集模式。`AUTO`=框架自动选择（默认AGGREGATE），`SINGLE`=单条缓冲，`AGGREGATE`=聚合刷写 |

#### 缓冲区配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `useBuffer` | `boolean` | `true` | 是否启用双阈值缓冲区（批量入库） |
| `maxBufferSize` | `int` | `100` | 单批次最大日志条数 |
| `maxBufferBytes` | `String` | `"1MB"` | 单次调用的缓冲区最大内存占用 |

#### 熔断降级配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enableDegrade` | `boolean` | `true` | 是否启用降级兜底 |
| `degradeFailThreshold` | `int` | `5` | 熔断判定最小样本数（达到后按滑动窗口失败率判定） |
| `degradeStorage` | `DegradeStorage` | `FILE` | 熔断后的兜底存储方式。可选：`FILE` / `LIMITED_MEMORY` / `DISCARD_NON_ERROR` / `DISCARD_ALL` |
| `recoverIntervalSeconds` | `int` | `30` | 熔断后探活的时间间隔（秒） |
| `recoverMaxIntervalSeconds` | `int` | `300` | 指数退避最大探活间隔（秒） |
| `halfOpenPassCount` | `int` | `3` | 半开状态放行请求数 |
| `halfOpenSuccessThreshold` | `int` | `3` | 半开成功切回正常的阈值 |
| `blockWhenDegradeFail` | `boolean` | `false` | 兜底也失败时是否向业务方法抛出 `LogCollectDegradeException`。**强烈建议保持 `false`**，仅审计强制场景开启 |

#### 安全防护配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enableSanitize` | `boolean` | `true` | 是否启用日志内容净化（防注入） |
| `sanitizer` | `Class` | `LogSanitizer.class` | 指定自定义净化器类型；不指定时自动装配 `com.logcollect.core.security.DefaultLogSanitizer` |
| `enableMask` | `boolean` | `true` | 是否启用敏感数据脱敏 |
| `masker` | `Class` | `LogMasker.class` | 指定自定义脱敏器类型；不指定时自动装配 `com.logcollect.core.security.DefaultLogMasker` |

#### 高级配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `handlerTimeoutMs` | `int` | `5000` | Handler before/after 执行超时（毫秒） |
| `transactionIsolation` | `boolean` | `false` | 是否在独立事务中执行 Handler |
| `maxNestingDepth` | `int` | `10` | 最大 `@LogCollect` 嵌套深度 |
| `maxTotalCollect` | `int` | `100000` | 单次方法调用最多收集日志条数 |
| `maxTotalCollectBytes` | `String` | `"50MB"` | 单次方法调用最多收集日志字节数 |
| `totalLimitPolicy` | `TotalLimitPolicy` | `STOP_COLLECTING` | 达到总量上限后的策略：`STOP_COLLECTING` / `DOWNGRADE_LEVEL` / `SAMPLE` |
| `samplingRate` | `double` | `1.0` | 采样比例（0~1），`1.0` 表示全量 |
| `samplingStrategy` | `SamplingStrategy` | `RATE` | 采样策略：`RATE` / `COUNT` / `ADAPTIVE` |
| `backpressure` | `Class` | `BackpressureCallback.class` | 背压回调（按缓冲利用率返回 `CONTINUE` / `SKIP_DEBUG_INFO` / `PAUSE`） |

**采样策略说明**

| 策略 | 行为 |
|------|------|
| `RATE` | 每条日志以 `samplingRate` 概率决定是否收集（随机采样） |
| `COUNT` | 每 `1/samplingRate` 条收集一条（如 `0.1` → 每 10 条收 1 条） |
| `ADAPTIVE` | 根据全局缓冲区水位自适应调整：`<=50%` 全量收集；`50%~80%` 按 `samplingRate` 采样；`>=80%` 仅收集 WARN/ERROR |

> `ADAPTIVE` 下 `samplingRate` 仅在中等水位区间（`50%~80%`）生效。

#### 可观测性配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enableMetrics` | `boolean` | `true` | 是否暴露 Metrics 指标 |

> `metricsPrefix` 为全局配置，仅支持 `logcollect.global.metrics.prefix`。
> 运行期配置中心修改 `metrics.prefix` 不会热更新（Meter 名称注册后不可变），需重启应用生效。
>
> FILE 降级存储参数（`max-total-size` / `ttl-days` / `encrypt-enabled`）为全局物理约束，仅支持 `logcollect.global.degrade.file.*`。
> 全局物理约束类 key（如 `degrade.file.*` / `buffer.total-max-bytes` / `guard.*`）在方法级配置会被统一忽略。

> `handlerTimeoutMs` 基于 `Future.cancel(true)` 实现超时中断。  
> 仅当 Handler 代码对线程中断有响应时才会生效；CPU 密集循环请显式检查 `Thread.currentThread().isInterrupted()`。  
> 仅作用于 `before()` / `after()`，不影响 `appendLog` / `flushAggregatedLog`。

### 8.2 常用配置组合

**定时任务（默认配置，聚合模式）**

```java
@LogCollect  // 全部使用默认值，聚合模式
public void dailyReconcile() { ... }
```

**数据导入（单条模式，逐条入明细表）**

```java
@LogCollect(
    collectMode = CollectMode.SINGLE,  // 需要逐条入库
    maxBufferSize = 50                 // 每50条 flush 一次
)
public void importData(List<DataRecord> records) { ... }
```

**支付接口（强一致 + 极端兜底）**

```java
@LogCollect(
    async = false,
    minLevel = "WARN",
    useBuffer = false,
    collectMode = CollectMode.SINGLE,
    degradeStorage = DegradeStorage.DISCARD_NON_ERROR,
    transactionIsolation = true
)
public PayResult pay(PayRequest request) { ... }
```

> ⚠️ `async=false, useBuffer=false` 时每条日志会走同步热路径（含净化/脱敏正则与持久化），会直接增加业务 RT。  
> 建议该组合仅用于 `minLevel="WARN"` 以上低频场景；高频场景优先使用默认 `async=true, useBuffer=true`。

**高并发场景（大缓冲 + 聚合模式）**

```java
@LogCollect(
    maxBufferSize = 500,
    maxBufferBytes = "5MB"
)
public void seckill(Long itemId) { ... }
```

**长任务总量保护（防止单次调用无限收集）**

```java
@LogCollect(
    maxTotalCollect = 100000,
    maxTotalCollectBytes = "50MB",
    totalLimitPolicy = TotalLimitPolicy.DOWNGRADE_LEVEL,
    samplingRate = 0.2,
    samplingStrategy = SamplingStrategy.ADAPTIVE
)
public void longRunningBatchJob() { ... }
```

**高压背压（回调感知缓冲压力）**

```java
@Component
public class PaymentBackpressureCallback implements BackpressureCallback {
    @Override
    public BackpressureAction onPressure(double utilization) {
        if (utilization >= 0.9d) {
            return BackpressureAction.PAUSE;
        }
        if (utilization >= 0.75d) {
            return BackpressureAction.SKIP_DEBUG_INFO;
        }
        return BackpressureAction.CONTINUE;
    }
}

@LogCollect(backpressure = PaymentBackpressureCallback.class)
public void processPay(PayRequest request) { ... }
```

**局部反向排除（`@LogCollectIgnore`）**

```java
@Service
public class HealthProbeService {
    @LogCollectIgnore
    public void healthCheck() {
        log.info("健康检查");  // ❌ 不收集
    }
}

@LogCollect
public void createOrder(OrderRequest req) {
    log.info("创建订单");                 // ✅ 收集
    healthProbeService.healthCheck();     // ❌ 被排除
}
```

---

## 九、全异步场景指南

### 9.1 场景覆盖总表

| 场景 | Boot 2.7 | Boot 3.x | 接入方式 | 示例 |
|------|----------|----------|---------|------|
| 同步方法 | ✅ 自动 | ✅ 自动 | 无需操作 | [9.2](#92-同步方法默认) |
| Spring `@Async`（默认 AsyncConfigurer） | ✅ 自动 | ✅ 自动 | 无需操作 | [9.3](#93-spring-async自动传播) |
| Spring `@Async`（自定义 AsyncConfigurer） | ⚠️ 需确认 | ⚠️ 需确认 | 确认 `TaskDecorator` 透传 | [9.3](#93-spring-async自动传播) |
| Spring 管理的 `ThreadPoolTaskExecutor` | ✅ 自动 | ✅ 自动 | 无需操作 | [9.4](#94-spring-线程池自动传播) |
| `CompletableFuture` + Spring 线程池 | ✅ 自动 | ✅ 自动 | 无需操作 | [9.5](#95-completablefuture--spring-线程池) |
| WebFlux `Mono`/`Flux` | ✅ 自动* | ✅ 自动 | 无需操作 | [9.6](#96-webflux-响应式) |
| Kotlin Coroutines | ⚠️ 一行代码 | ⚠️ 一行代码 | `withContext(LogCollectCoroutineContext())` | - |
| Spring Bean `ExecutorService` | ✅ 自动 | ✅ 自动 | BPP 自动包装 | [9.7](#97-executorservice-自动与手动包装) |
| 手动 `ExecutorService` | ⚠️ 一行代码 | ⚠️ 一行代码 | 工具类包装 | [9.7](#97-executorservice-自动与手动包装) |
| 直接 `new Thread()` | ⚠️ 一行代码 | ⚠️ 一行代码 | 工具类包装 | [9.8](#98-直接-new-thread工具类) |
| 第三方库回调 | ⚠️ 一行代码 | ⚠️ 一行代码 | 工具类包装 | [9.9](#99-第三方回调工具类) |
| `ForkJoinPool` / `parallelStream` | ⚠️ 一行代码 | ⚠️ 一行代码 | 工具类包装 | [9.10](#910-forkjoinpool) |
| 嵌套 `@LogCollect` | ✅ 自动 | ✅ 自动 | 栈式隔离 | [9.11](#911-嵌套-logcollect) |
| Servlet `AsyncContext` | ✅ 自动 | ✅ 自动 | 无需操作 | - |

> **✅ 自动**：框架完全自动处理，用户零配置  
> **⚠️ 一行代码**：需使用 `LogCollectContextUtils` 包装  
> **\***：Boot 2.7 + Reactor 3.4.x 需框架手动 Hook（已自动处理）；Reactor 3.5.3+ 全自动

### 9.2 同步方法（默认）

```java
@LogCollect
public void syncProcess() {
    log.info("这条日志会被收集");       // ✅
    log.debug("低于 INFO 级别，忽略");  // ❌ 默认 level=INFO
}
```

### 9.3 Spring @Async（自动传播）

```java
@LogCollect
public void batchProcess() {
    log.info("主线程");                   // ✅
    asyncService.processPartA();          // ✅ 子线程日志自动聚合
    asyncService.processPartB();          // ✅ 子线程日志自动聚合
}

@Service
public class AsyncService {
    @Async
    public void processPartA() {
        log.info("子线程 A");             // ✅ 归属同一 traceId
    }
    @Async
    public void processPartB() {
        log.info("子线程 B");             // ✅ 归属同一 traceId
    }
}
```

### 9.4 Spring 线程池（自动传播）

```java
@Configuration
public class ThreadPoolConfig {
    @Bean
    public ThreadPoolTaskExecutor businessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setThreadNamePrefix("business-");
        // 无需手动设置 TaskDecorator，框架 BeanPostProcessor 自动包装
        return executor;
    }
}

@Service
public class OrderService {
    @Autowired
    private ThreadPoolTaskExecutor businessExecutor;

    @LogCollect
    public void createOrder(OrderRequest req) {
        log.info("创建订单");                              // ✅
        businessExecutor.submit(() -> {
            log.info("子线程处理库存扣减");                 // ✅ 自动聚合
        });
    }
}
```

### 9.5 CompletableFuture + Spring 线程池

```java
@LogCollect
public void analyze() {
    CompletableFuture<Integer> f1 = CompletableFuture.supplyAsync(() -> {
        log.info("计算指标 A");   // ✅ 通过 Spring 线程池自动传播
        return calculateA();
    }, springManagedExecutor);

    CompletableFuture<Integer> f2 = CompletableFuture.supplyAsync(() -> {
        log.info("计算指标 B");   // ✅
        return calculateB();
    }, springManagedExecutor);

    CompletableFuture.allOf(f1, f2).join();
}
```

### 9.6 WebFlux 响应式

```java
@LogCollect(handler = OrderLogHandler.class)
@PostMapping("/orders")
public Mono<Order> createOrder(@RequestBody OrderRequest req) {
    return Mono.just(req)
        .flatMap(r -> {
            log.info("校验参数: {}", r.getPhone());    // ✅ 自动脱敏 + 聚合
            return validateOrder(r);
        })
        .publishOn(Schedulers.boundedElastic())        // 切换线程
        .flatMap(r -> {
            log.info("扣减库存");                       // ✅ 切换线程后仍自动聚合
            return deductStock(r);
        })
        .map(order -> {
            log.info("订单创建成功: {}", order.getId()); // ✅
            return order;
        });
}
```

> **Reactor 版本说明**：
> - Reactor 3.5.3+（Boot 3.x 默认）：原生 `Hooks.enableAutomaticContextPropagation()` 全自动传播。
> - Reactor 3.4.x（Boot 2.7 默认）：框架通过 `Hooks.onEachOperator()` 注册 `CoreSubscriber` 包装器，在每个操作符的 `onNext`/`onError`/`onComplete` 回调前恢复 `ThreadLocal`，回调后清理，实现等价效果。

### 9.7 ExecutorService（自动与手动包装）

```java
// Spring Bean 的 ExecutorService 会由 BeanPostProcessor 自动包装；
// 手动创建（非 Spring 管理）则需要一行代码包装
private final ExecutorService rawPool = Executors.newFixedThreadPool(8);
private final ExecutorService pool =
    LogCollectContextUtils.wrapExecutorService(rawPool);  // ← 一行包装

@LogCollect
public void importData(List<DataRecord> records) {
    for (DataRecord record : records) {
        pool.submit(() -> {
            log.info("导入记录: {}", record.getId());   // ✅ 自动聚合
        });
    }
}
```

### 9.8 直接 new Thread（工具类）

```java
@LogCollect
public void legacyProcess() {
    // 方式一：包装 Runnable
    Thread t1 = new Thread(
        LogCollectContextUtils.wrapRunnable(() -> {
            log.info("线程 1");   // ✅
        }), "worker-1");
    t1.start();

    // 方式二：工具类直接创建
    Thread t2 = LogCollectContextUtils.newThread(() -> {
        log.info("线程 2");       // ✅
    }, "worker-2");
    t2.start();

    // 方式三：守护线程
    Thread t3 = LogCollectContextUtils.newDaemonThread(() -> {
        log.info("守护线程");     // ✅
    }, "daemon-1");
    t3.start();
}
```

### 9.9 第三方回调（工具类）

```java
@LogCollect
public void mqConsume() {
    mqClient.onMessage(LogCollectContextUtils.wrapConsumer(msg -> {
        log.info("收到消息: {}", msg.getId());   // ✅
    }));
}
```

### 9.10 ForkJoinPool

```java
@LogCollect
public void parallelProcess(List<Item> items) {
    ForkJoinPool customPool = new ForkJoinPool(4);

    customPool.submit(LogCollectContextUtils.wrapRunnable(() ->
        items.parallelStream().forEach(item -> {
            log.info("处理: {}", item.getId());  // ✅
        })
    )).join();

    customPool.shutdown();
}
```

### 9.11 嵌套 @LogCollect

```java
@LogCollect(handler = OrderLogHandler.class)
public void createOrder(OrderRequest req) {
    log.info("创建订单");           // → traceId-A → OrderLogHandler

    riskService.riskCheck(req);     // 嵌套调用

    log.info("订单创建完成");       // → traceId-A → OrderLogHandler（自动恢复）
}

@LogCollect(handler = RiskLogHandler.class)
public void riskCheck(OrderRequest req) {
    log.info("风控检查");           // → traceId-B → RiskLogHandler
    // 方法结束自动 pop，恢复外层 traceId-A
}
```

### 9.12 `LogCollectContextUtils` API 速查

| 方法 | 适用场景 | 签名 |
|------|---------|------|
| `wrapRunnable` | `new Thread()` / 回调 | `Runnable wrapRunnable(Runnable)` |
| `wrapCallable` | `ExecutorService.submit(Callable)` | `<V> Callable<V> wrapCallable(Callable<V>)` |
| `wrapConsumer` | 三方消息回调（带入参） | `<T> Consumer<T> wrapConsumer(Consumer<T>)` |
| `wrapExecutorService` | 手动创建的线程池 | `ExecutorService wrapExecutorService(ExecutorService)` |
| `wrapScheduledExecutorService` | 定时任务线程池 | `ScheduledExecutorService wrapScheduledExecutorService(ScheduledExecutorService)` |
| `LogCollectExecutors.wrap` | 统一包装现有线程池 | `ExecutorService wrap(ExecutorService)` |
| `wrapExecutor` | 通用 Executor | `Executor wrapExecutor(Executor)` |
| `newThread` | 直接创建线程 | `Thread newThread(Runnable, String)` |
| `newDaemonThread` | 创建守护线程 | `Thread newDaemonThread(Runnable, String)` |
| `threadFactory` | 自定义线程池工厂 | `ThreadFactory threadFactory(String)` |
| `wrapThreadFactory` | 包装已有 ThreadFactory | `ThreadFactory wrapThreadFactory(ThreadFactory)` |
| `supplyAsync` | CF 增强 | `<U> CompletableFuture<U> supplyAsync(Supplier<U>)` |
| `runAsync` | CF 增强 | `CompletableFuture<Void> runAsync(Runnable)` |
| `isInLogCollectContext` | 诊断 | `boolean isInLogCollectContext()` |
| `diagnosticInfo` | 诊断 | `String diagnosticInfo()` |

**内存安全保障**：`wrap*` 方法使用弱引用捕获上下文，子线程 `finally` 中强制清理 `ThreadLocal`。若外层方法已结束且上下文被回收，延迟任务会安全降级为“无上下文执行”。

> 权衡：弱引用方案优先避免长生命周期任务持有上下文造成泄漏；若任务延迟很久执行，可能因上下文已回收而不再传播。

> `wrapThreadFactory` 仅接收 `java.util.concurrent.ThreadFactory`。  
> `ForkJoinWorkerThreadFactory` 不是 `ThreadFactory` 子类型，ForkJoinPool 场景请优先包装提交任务（`wrapRunnable`）。
> `wrapExecutorService` 传入 `ScheduledExecutorService` 时会保留调度语义，返回值可安全强转为 `ScheduledExecutorService`。

---

## 十、安全防护体系

### 10.1 九层纵深防御总览

```
威胁                   防护层                    状态
─────────────────────────────────────────────────────
① 超长日志/堆栈    →   StringLengthGuard       → 默认开启
② 日志注入攻击    →   SecurityPipeline         → 默认开启
③ 敏感数据泄露    →   LogMasker               → 默认开启
④ SQL 注入        →   安全基类                 → 可选继承
⑤ MDC 串日志/嵌套 →   栈式上下文管理           → 框架内置
⑥ 降级文件风险    →   DegradeFileManager       → 框架内置
⑦ 熔断恢复风暴    →   三状态熔断器             → 框架内置
⑧ ThreadLocal 泄漏→   全场景 finally 清理      → 框架内置
⑨ ReDoS 攻击     →   RegexSafetyValidator     → 自定义规则时校验
```

### 10.2 日志注入防护

攻击者可通过可控输入（如请求参数）注入换行符伪造日志条目：

```java
// 攻击载荷：username = "admin\n2026-01-01 INFO 支付成功 amount=0"
log.info("用户登录: {}", username);
// 未防护 → 日志中出现一条伪造的"支付成功"记录
// 已防护 → 换行符被替换为空格，伪造无效
```

`SecurityPipeline` 是端到端唯一安全入口：Appender 只做字段提取与长度守卫，不再做前置 sanitize，避免重复处理。

**默认净化器** `com.logcollect.core.security.DefaultLogSanitizer` 处理策略：

| 场景 | 处理方式 |
|------|---------|
| 消息 `sanitize(raw)` | `\r\n\t` 与控制字符替换为空格，去除 HTML/ANSI |
| 堆栈 `sanitizeThrowable(raw)` | 保留换行与缩进，仅清理危险控制字符和 HTML/ANSI |
| 堆栈注入防御 | 非标准堆栈行会标记为 `\t[ex-msg] ...`，防止伪造日志行 |

长度守卫配置（默认）：
- `logcollect.global.guard.max-content-length=32768`
- `logcollect.global.guard.max-throwable-length=65536`

`LogSanitizer` 还提供 `sanitizeWithStats()` / `sanitizeThrowableWithStats()` 默认方法，用于命中统计且不增加二次净化开销。

**自定义扩展**：

```java
@Component
public class FinanceLogSanitizer extends DefaultLogSanitizer {
    private static final Pattern SQL_KW =
        Pattern.compile("(?i)(DROP|DELETE|UPDATE|INSERT|ALTER)\\s");
    
    @Override
    public String sanitize(String raw) {
        String result = super.sanitize(raw);
        return SQL_KW.matcher(result).replaceAll("[SQL_FILTERED] ");
    }
}

// 使用
@LogCollect(sanitizer = FinanceLogSanitizer.class)
```

### 10.3 敏感数据脱敏

**默认脱敏器** `DefaultLogMasker` 内置规则：

| 数据类型 | 示例 | 脱敏结果 |
|---------|------|---------|
| 手机号 | `13812345678` | `138****5678` |
| 身份证号 | `110105199001011234` | `110105********1234` |
| 银行卡号 | `6222021234567890123` | `6222****0123` |
| 邮箱 | `zhangsan@example.com` | `zh****@example.com` |

**自定义扩展**：

```java
@Component
public class BusinessLogMasker extends DefaultLogMasker {
    public BusinessLogMasker() {
        super();
        addRule(
            Pattern.compile("(?i)(password|pwd|secret)[=:\"\\s]+\\S+"),
            m -> m.group().replaceAll("([=:\"\\s]+)\\S+", "$1******")
        );
    }
}
```

> **ReDoS 防护**：`DefaultLogMasker#addRule(...)` 注册自定义正则时会经过 `RegexSafetyValidator` 校验，运行期执行还带超时保护（默认 `50ms`，超时自动降级为返回净化后的原文）。  
> 运行期“配置中心动态下发正则规则”能力暂不支持，计划在后续版本补充。

### 10.4 SQL 注入防护

框架提供安全基类，强制使用参数化查询：

```java
@Component
public class TaskLogHandler extends AbstractJdbcLogCollectHandler {

    @Override
    protected String tableName() {
        return "task_log_detail";
    }

    @Override
    protected Map<String, Object> buildInsertParams(
            String traceId, String content, String level, LocalDateTime time) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("trace_id", traceId);
        params.put("content", content);
        params.put("level", level);
        params.put("created_at", time);
        return params;
    }
}
```

### 10.5 降级文件安全

| 防护项 | 措施 |
|--------|------|
| 路径遍历 | traceId 由框架内部 `UUID.randomUUID()` 生成；`DegradeFileManager` 对传入值做 UUID 正则校验，非法值自动替换为新 UUID |
| 权限控制 | Linux/Mac：`rw-------` (600)；Windows：ACL 限制仅 owner 读写 |
| 磁盘耗尽 | 总大小上限（默认 500MB） + TTL 自动清理（默认 90 天） + 磁盘可用空间 < 100MB 时停止写入 |
| 数据泄露 | 降级文件同样经过 Sanitizer + Masker 处理；可选 AES-256-GCM 加密 |
| 密钥管理 | 加密密钥来源优先级：KMS > 环境变量 `LOGCOLLECT_DEGRADE_FILE_KEY` > Spring Vault > 配置文件（仅开发环境）。生产环境按多维度判定（prod profile / K8s / Cloud Foundry / 非显式 dev-test）并拒绝配置文件密钥 |

### 10.6 事务隔离

默认情况下，`handler.before()/after()` 与业务方法处于同一事务。业务事务回滚时，日志也会回滚。

```java
// 启用独立事务：日志操作在 REQUIRES_NEW 事务中执行
@LogCollect(transactionIsolation = true)
public void criticalOperation() { ... }
```

> 开启后，`before()` / `after()` / `appendLog()` / `flushAggregatedLog()` 均在独立事务执行，日志不受业务事务回滚影响。  
> `async=true + transactionIsolation=true` 组合下，异步 flush 也在独立事务中执行，Handler 不应依赖业务主事务中尚未提交的数据。

---

## 十一、熔断降级机制

### 11.1 四层分层降级

```
第一层：流量削峰
  缓冲区/异步队列满量 → 丢弃 DEBUG/INFO，保留 WARN/ERROR

第二层：三状态熔断
  CLOSED ──窗口失败率达阈值──→ OPEN ──探活成功──→ HALF_OPEN ──连续成功──→ CLOSED
                             ↑                      │
                             └──────探活失败──────────┘
                                   （间隔×2，上限 300s，±20% 随机抖动）

第三层：兜底存储
  FILE           → 安全文件（UUID 名 + 权限 600 + 空间上限 + TTL 90天 + 可选加密）
  LIMITED_MEMORY → 固定长度内存队列
  DISCARD_NON_ERROR → 仅保留 ERROR 级日志
  DISCARD_ALL    → 丢弃全部日志（零 IO 开销，仅记 Metrics）

第四层：终极兜底
  兜底存储也失败 → 默认丢弃日志；若 `blockWhenDegradeFail=true` 则抛 `LogCollectDegradeException`
```

补充可靠性机制：
- 应用关闭阶段由 `LogCollectLifecycle` 触发 `forceFlush()`，尽量减少未落库日志丢失。
- 若 flush 仍失败，框架会执行应急 dump 到 `${java.io.tmpdir}`（`logcollect-emergency-*.log` / `logcollect-fallback/*.log`）。

### 11.2 三状态熔断器详解

```
┌──────────┐   窗口失败率达阈值  ┌──────────┐
│  CLOSED  │ ──────────────→   │   OPEN   │
│ (正常写入)│                    │ (熔断中)  │
│          │ ←────────────────  │          │
└──────────┘  半开全部成功       └────┬─────┘
      ↑                              │
      │                   探活间隔到达 │
      │                              ↓
      │                        ┌───────────┐
      │    连续N次成功          │ HALF_OPEN  │
      └────────────────────── │ (半开探测)  │
                               └──────┬─────┘
                                      │
                                 探测失败
                                      │
                                      ↓
                              回到 OPEN + 间隔×2
                              (指数退避 + ±20% 随机抖动防惊群)
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `degradeFailThreshold` | 5 | 熔断判定最小样本数（窗口内样本达到该值后再判失败率） |
| `degrade.window-size` | 10 | 滑动窗口大小（请求数） |
| `degrade.failure-rate-threshold` | 0.6 | 失败率阈值（达到即进入 OPEN） |
| `recoverIntervalSeconds` | 30 | 初始探活间隔 |
| `recoverMaxIntervalSeconds` | 300 | 指数退避上限 |
| `halfOpenPassCount` | 3 | 半开状态放行的请求数 |
| `halfOpenSuccessThreshold` | 3 | 半开连续成功多少次切回 CLOSED |

**手动重置**：通过 [Actuator 管理端点](#十四actuator-管理端点) 手动重置熔断器。

---

## 十二、配置中心集成

### 12.1 架构

```
┌──────────────────────────────────────────────────────────────────────────┐
│  LogCollectConfigResolver（四级配置合并引擎）                              │
│  框架默认 ← @LogCollect注解 ← 配置中心全局 ← 配置中心方法级              │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 ↓
┌──────────┬──────────┬──────────────────┬───────────────────┐
│ Nacos    │ Apollo   │ Spring Cloud     │ 预留 SPI 扩展      │
│ 适配器   │ 适配器    │ Config 适配器     │ • Consul / Etcd   │
│ order=100│ order=100│ order=200        │ • 自定义实现        │
└──────────┴──────────┴──────────────────┴───────────────────┘

条件装配：@ConditionalOnClass 自动发现配置中心依赖
平滑切换：Spring Environment 抽象 + `order` 优先级（值越小优先）
变更监听：配置变更 → 缓存清除 → 下次调用即时生效
本地缓存：配置中心不可用时，使用最后一次有效配置（默认保留 7 天）
```

### 12.2 接入方式

**Nacos**

```xml
<dependency>
    <groupId>io.github.mora-na</groupId>
    <!-- <groupId>org.dpdns.mora</groupId> -->
    <artifactId>logcollect-config-nacos</artifactId>
</dependency>
```

```yaml
logcollect:
  config:
    nacos:
      enabled: true
      data-id: logcollect-config
      group: DEFAULT_GROUP
```

**Apollo**

```xml
<dependency>
    <groupId>io.github.mora-na</groupId>
    <!-- <groupId>org.dpdns.mora</groupId> -->
    <artifactId>logcollect-config-apollo</artifactId>
</dependency>
```

```yaml
logcollect:
  config:
    apollo:
      enabled: true
      namespace: logcollect
```

### 12.3 配置 Key 命名规范

```properties
# ========== 全局配置 ==========
logcollect.global.enabled=true
logcollect.debug=false
logcollect.global.async=true
logcollect.global.level=INFO
logcollect.global.exclude-loggers=
logcollect.global.collect-mode=AUTO
logcollect.global.format.log-line-pattern=%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%t] %c{1} - %m%ex%n
logcollect.global.buffer.enabled=true
logcollect.global.buffer.max-size=100
logcollect.global.buffer.max-bytes=1MB
logcollect.global.buffer.overflow-strategy=FLUSH_EARLY
logcollect.global.buffer.total-max-bytes=100MB
logcollect.global.degrade.enabled=true
logcollect.global.degrade.fail-threshold=5
logcollect.global.degrade.storage=FILE
logcollect.global.degrade.recover-interval-seconds=30
logcollect.global.degrade.window-size=10
logcollect.global.degrade.failure-rate-threshold=0.6
logcollect.global.degrade.file.max-total-size=500MB
logcollect.global.degrade.file.ttl-days=90
logcollect.global.security.sanitize.enabled=true
logcollect.global.security.mask.enabled=true
logcollect.global.guard.max-content-length=32768
logcollect.global.guard.max-throwable-length=65536
logcollect.global.handler-timeout-ms=5000
logcollect.global.max-nesting-depth=10
logcollect.global.max-total-collect=100000
logcollect.global.max-total-collect-bytes=50MB
logcollect.global.total-limit-policy=STOP_COLLECTING
logcollect.global.sampling-rate=1.0
logcollect.global.sampling-strategy=RATE
logcollect.global.metrics.enabled=true
logcollect.internal.log-level=INFO

# ========== 方法级配置 ==========
logcollect.methods.com_example_service_OrderService_pay.level=ERROR
logcollect.methods.com_example_service_OrderService_pay.async=false
logcollect.methods.com_example_service_OrderService_pay.collect-mode=SINGLE
logcollect.methods.com_example_service_OrderService_pay.max-total-collect=500000
logcollect.methods.com_example_service_OrderService_pay.sampling-rate=0.1
logcollect.methods.com_example_job_ReconcileJob_execute.buffer.max-size=500
```

### 12.4 动态调整示例

```properties
# 紧急降级：Nacos 推送，立即生效
logcollect.global.level=ERROR

# 临时关闭脱敏（排查问题时）
logcollect.global.security.mask.enabled=false

# 切换收集模式
logcollect.global.collect-mode=SINGLE

# 一键关闭日志收集（紧急预案）
logcollect.global.enabled=false
```

---

## 十三、可观测性

### 13.1 Metrics 指标

框架自动集成 Micrometer，对接 Prometheus / Grafana。

**计数器**

| 指标 | 标签 | 说明 |
|------|------|------|
| `logcollect.collected.total` | `level`, `method`, `mode` | 收集的日志总数 |
| `logcollect.discarded.total` | `reason`, `method` | 丢弃的日志总数。reason: `buffer_full` / `global_memory_limit` / `level_filter` / `logger_filter` / `handler_filter` / `circuit_open` 等 |
| `logcollect.persisted.total` | `method`, `mode` | 入库成功总数 |
| `logcollect.persist.failed.total` | `method` | 入库失败总数 |
| `logcollect.flush.total` | `method`, `mode`, `trigger` | flush 次数。trigger: `threshold` / `final` |
| `logcollect.degrade.triggered.total` | `type`, `method` | 降级触发次数 |
| `logcollect.circuit.recovered.total` | `method` | 熔断恢复次数 |
| `logcollect.security.sanitize.hits.total` | `method` | 净化器命中次数 |
| `logcollect.security.mask.hits.total` | `method` | 脱敏器命中次数 |
| `logcollect.config.refresh.total` | `source` | 配置刷新次数 |
| `logcollect.handler.timeout.total` | `method` | Handler 超时次数 |

**仪表盘（实时值）**

| 指标 | 标签 | 说明 |
|------|------|------|
| `logcollect.buffer.utilization` | `method` | 缓冲区使用率 (0.0~1.0) |
| `logcollect.buffer.global.utilization` | - | 全局缓冲区使用率 |
| `logcollect.circuit.state` | `method` | 熔断器状态 (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| `logcollect.degrade.file.total.bytes` | - | 降级文件总大小 |
| `logcollect.degrade.file.count` | - | 降级文件数量 |
| `logcollect.active.collections` | - | 当前活跃的 @LogCollect 数 |

> `metrics.prefix` 为启动期参数。运行期通过配置中心修改不会热更新，需重启应用生效。

**计时器**（含 P50 / P95 / P99）

| 指标 | 标签 | 说明 |
|------|------|------|
| `logcollect.persist.duration` | `method`, `mode` | 单批次入库耗时 |
| `logcollect.security.pipeline.duration` | `method` | 安全流水线耗时 |
| `logcollect.handler.duration` | `method`, `phase` | Handler 执行耗时 |

### 13.2 健康检查

```
GET /actuator/health/logcollect
```

```json
{
  "status": "UP",
  "details": {
    "circuitBreakers": {
      "com.example.OrderService#placeOrder": "CLOSED",
      "com.example.ReconcileJob#execute": "CLOSED"
    },
    "activeCollections": 3,
    "totalCollected": 258420,
    "totalPersisted": 258388,
    "totalDiscarded": 32,
    "totalFlushes": 2584,
    "bufferUtilization": {
      "com.example.ReconcileJob#execute": 0.23
    },
    "globalBufferUtilization": "2.23%",
    "collectModes": {
      "com.example.OrderService#placeOrder": "SINGLE",
      "com.example.ReconcileJob#execute": "AGGREGATE"
    },
    "degradeFileCount": 0,
    "degradeFileTotalSize": "0 B",
    "degradeFileDiskFreeSpace": "50.00 GB",
    "sanitizeHits": 12,
    "maskHits": 1847,
    "lastPersistDurationP99": "4.2ms",
    "configSources": [
      "nacos(order=100,available=true)"
    ],
    "logFramework": "Log4j2 (AsyncLogger + Disruptor)",
    "contextPropagation": true,
    "springBootVersion": "2.7.18"
  }
}
```

状态判定规则：

| 状态 | 条件 |
|------|------|
| `UP` | 所有熔断器 CLOSED |
| `DEGRADED` | 有熔断器处于 HALF_OPEN |
| `DOWN` | 有熔断器处于 OPEN |

### 13.3 Prometheus 告警规则示例

```yaml
groups:
  - name: logcollect
    rules:
      - alert: LogCollectCircuitBreakerOpen
        expr: logcollect_circuit_state > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "LogCollect 熔断器打开: {{ $labels.method }}"

      - alert: LogCollectHighDiscardRate
        expr: rate(logcollect_discarded_total[5m]) > 10
        for: 2m
        labels:
          severity: warning

      - alert: LogCollectBufferHighUtilization
        expr: logcollect_buffer_global_utilization > 0.8
        for: 3m
        labels:
          severity: warning

      - alert: LogCollectDegradeDiskLow
        expr: logcollect_degrade_file_disk_free_bytes < 1073741824
        for: 5m
        labels:
          severity: critical
```

---

## 十四、Actuator 管理端点

框架集成 Spring Boot Actuator，提供运行时管理能力。

> **前置条件**：项目引入 `spring-boot-starter-actuator`，并暴露 `logcollect` 端点：
> ```yaml
> management:
>   endpoints:
>     web:
>       exposure:
>         include: health,logcollect
> ```

### 14.1 端点总览

| HTTP 方法 | 路径 | 说明 |
|-----------|------|------|
| `GET` | `/actuator/logcollect/status` | 查看框架全局运行时状态 |
| `POST` | `/actuator/logcollect/circuitBreakerReset` | 重置熔断器 |
| `POST` | `/actuator/logcollect/refreshConfig` | 手动刷新配置 |
| `POST` | `/actuator/logcollect/cleanupDegradeFiles` | 清理降级文件 |
| `PUT` | `/actuator/logcollect/enabled` | 全局开关 |

### 14.2 查看全局状态

```
GET /actuator/logcollect/status
```

**响应示例**

```json
{
  "enabled": true,
  "circuitBreakers": {
    "com.example.service.OrderService#placeOrder": {
      "state": "CLOSED",
      "consecutiveFailures": 0,
      "lastFailureTime": "never",
      "currentRecoverIntervalMs": 30000
    },
    "com.example.job.ReconcileJob#execute": {
      "state": "OPEN",
      "consecutiveFailures": 7,
      "lastFailureTime": "2026-02-28T07:30:12.456Z",
      "currentRecoverIntervalMs": 60000
    }
  },
  "registeredMethods": {
    "com.example.service.OrderService#placeOrder": {
      "collectMode": "SINGLE"
    },
    "com.example.job.ReconcileJob#execute": {
      "collectMode": "AGGREGATE"
    }
  },
  "globalBuffer": {
    "totalUsedBytes": 2341876,
    "maxTotalBytes": 104857600,
    "utilization": "2.23%"
  },
  "degradeFiles": {
    "fileCount": 3,
    "totalSizeBytes": 156234,
    "totalSizeHuman": "152.6 KB",
    "maxTotalSizeBytes": 524288000,
    "ttlDays": 90,
    "diskFreeSpaceBytes": 53687091200,
    "diskFreeSpaceHuman": "50.00 GB",
    "baseDir": "/app/logs/logCollect"
  },
  "configSources": [
    { "type": "nacos", "order": 100, "available": true },
    { "type": "spring-cloud-config", "order": 200, "available": true }
  ],
  "configCacheSize": 2,
  "lastConfigRefreshTime": "2026-02-28T07:25:00Z"
}
```

### 14.3 重置熔断器

**重置指定方法的熔断器**

```
POST /actuator/logcollect/circuitBreakerReset?method=com.example.job.ReconcileJob#execute
```

```json
{
  "method": "com.example.job.ReconcileJob#execute",
  "previousState": "OPEN",
  "currentState": "CLOSED",
  "resetTime": "2026-02-28T07:35:22.123Z"
}
```

**重置所有熔断器**（不传 `method` 参数）

```
POST /actuator/logcollect/circuitBreakerReset
```

```json
{
  "resetCount": 2,
  "details": [
    {
      "method": "com.example.service.OrderService#placeOrder",
      "previousState": "CLOSED",
      "currentState": "CLOSED"
    },
    {
      "method": "com.example.job.ReconcileJob#execute",
      "previousState": "OPEN",
      "currentState": "CLOSED"
    }
  ],
  "resetTime": "2026-02-28T07:35:22.123Z"
}
```

**方法不存在时**（HTTP 404）

```json
{
  "error": "Method not found: com.example.Foo#bar",
  "registeredMethods": [
    "com.example.service.OrderService#placeOrder",
    "com.example.job.ReconcileJob#execute"
  ]
}
```

### 14.4 手动刷新配置

```
POST /actuator/logcollect/refreshConfig
```

```json
{
  "configSources": [
    { "type": "nacos", "order": 100, "status": "refreshed" },
    { "type": "spring-cloud-config", "order": 200, "status": "refreshed" }
  ],
  "cacheClearedCount": 2,
  "localCacheSaved": true,
  "refreshTime": "2026-02-28T07:36:00Z"
}
```

> **频率限制**：最小间隔 10 秒，过于频繁的请求返回 HTTP 429。

### 14.5 清理降级文件

**常规清理（仅删除过期文件）**

```
POST /actuator/logcollect/cleanupDegradeFiles
```

**强制清理（删除所有降级文件，紧急释放磁盘）**

```
POST /actuator/logcollect/cleanupDegradeFiles?force=true
```

> **频率限制**：`force=true` 最小间隔 60 秒。

### 14.6 全局开关

```
PUT /actuator/logcollect/enabled?value=false
```

```json
{
  "previousEnabled": true,
  "currentEnabled": false,
  "updateTime": "2026-02-28T07:38:00Z"
}
```

关闭后，所有 `@LogCollect` 注解直接跳过，业务方法正常执行，**极低开销（仅 AOP 入口判断）**。

> **这是最重要的紧急预案入口**。如果框架出现任何影响业务的问题，通过此端点或配置中心推送 `logcollect.global.enabled=false` 可一键关闭。

### 14.7 安全配置建议

```yaml
management:
  server:
    port: 8081                # 管理端口与业务端口分离
    address: 127.0.0.1        # 仅本机/内网访问
  endpoints:
    web:
      exposure:
        include: health,logcollect
  endpoint:
    logcollect:
      enabled: true
  security:
    user:
      name: admin
      password: ${ACTUATOR_PASSWORD}
      roles: ADMIN
```

所有写操作（POST / PUT）会写入独立审计文件（不参与 `@LogCollect` 收集，且不走 Actuator 清理逻辑）。

> 强烈建议对 `/actuator/logcollect/**` 写操作启用 Spring Security 角色鉴权。  
> 框架在检测到 Spring Security 时沿用其鉴权与 CSRF 机制；未检测到 Spring Security 时，写操作会被保护过滤器直接拒绝（HTTP 403）。

---

## 十五、项目结构

```
logcollect-parent/
│
├── logcollect-bom/                            BOM：版本统一管理
│
├── logcollect-api/                            API 层：零外部依赖
│   ├── annotation/                            核心注解 + CollectMode 枚举
│   ├── handler/                               LogCollectHandler 接口
│   ├── model/                                 LogCollectContext / LogEntry / AggregatedLog
│   ├── format/                                LogLineDefaults / LogLinePatternParser
│   ├── sanitizer/                             净化器接口
│   ├── masker/                                脱敏器接口
│   ├── config/                                配置源接口
│   └── enums/                                 枚举
│
├── logcollect-core/                           核心逻辑
│   ├── context/                               栈式上下文管理 + 工具类
│   ├── format/                                ConsolePatternDetector / PatternCleaner / PatternValidator
│   ├── security/                              DefaultLogSanitizer / StringLengthGuard / 安全组件注册
│   ├── pipeline/                              SecurityPipeline / SinglePassSecurityProcessor
│   ├── buffer/                                SingleModeBuffer / AggregateModeBuffer / BoundedBufferPolicy
│   ├── circuitbreaker/                        三状态熔断器
│   ├── degrade/                               降级文件管理 + 加密 + 密钥
│   ├── config/                                配置合并引擎 + 本地缓存
│   ├── mdc/                                   统一 MDC 适配
│   └── internal/                              内部日志
│
├── logcollect-logback-adapter/                Logback 适配器
├── logcollect-log4j2-adapter/                 Log4j2 适配器
│
├── logcollect-spring-boot-autoconfigure/      自动装配
│   ├── aop/                                   AOP 切面（含模式分发逻辑）
│   ├── async/                                 TaskDecorator + AsyncConfigurer + BPP
│   ├── reactive/                              Reactor Context Propagation
│   ├── metrics/                               Metrics + HealthIndicator
│   ├── management/                            Actuator 管理端点
│   ├── circuitbreaker/                        熔断器注册表（Spring 生命周期管理）
│   ├── jdbc/                                  安全基类 + 事务隔离
│   ├── servlet/                               Servlet AsyncContext
│   └── ConsolePatternInitializer / LogCollectLifecycle / BufferRegistry
│
├── logcollect-spring-boot-starter/            Starter（JAR 聚合入口）
│
├── logcollect-config-nacos/                   Nacos 配置中心适配
├── logcollect-config-apollo/                  Apollo 配置中心适配
├── logcollect-config-spring-cloud/            Spring Cloud Config 适配
│
├── logcollect-test-support/                   测试支持工具
│   ├── InMemoryLogCollectHandler              内存测试 Handler
│   ├── LogCollectTestUtils                    测试工具
│   └── LogCollectAssertions                   断言工具
│
└── logcollect-samples/                        示例工程
    ├── logcollect-sample-boot27-logback/      Boot 2.7 + Logback
    ├── logcollect-sample-boot27-log4j2/       Boot 2.7 + Log4j2
    ├── logcollect-sample-boot3/               Boot 3.x
    ├── logcollect-sample-webflux/             WebFlux 响应式
    ├── logcollect-sample-all-async/           全异步场景覆盖
    └── logcollect-sample-nacos/               Nacos 配置中心
```

### 15.1 最近两轮整改摘要

**上次重构（结构升级）**

| 模块 | 关键变更 |
|------|---------|
| `logcollect-api` | `LogEntry` 去除 `formattedLine`，新增 `throwableString`/`timestamp`/Builder；新增 `LogLinePatternParser`、`LogLineDefaults`；`LogSanitizer` 增加 `sanitizeThrowable` |
| `logcollect-core` | 新增 `ConsolePatternDetector` SPI + `PatternCleaner`；`DefaultLogSanitizer` 支持堆栈净化；`SecurityPipeline` 拆分消息/堆栈处理 |
| `logcollect-logback-adapter` | Appender 提取异常堆栈；新增 `LogbackConsolePatternDetector` |
| `logcollect-log4j2-adapter` | Appender 深拷贝字段防 `MutableLogEvent` 回收；新增 `Log4j2ConsolePatternDetector` |
| `autoconfigure` | `ConsolePatternInitializer` 启动注入 pattern；`AggregateModeBuffer` 改为“入缓冲即格式化” |

**本次整改（安全与可靠性）**

| 维度 | 关键变更 |
|------|---------|
| 安全 | 新增 `StringLengthGuard`；`SecurityPipeline` 统一单入口；`thread/logger/level` 与 MDC 全字段净化；堆栈注入标记 `[ex-msg]` |
| 可靠性 | `BoundedBufferPolicy` 上限与溢出策略；`ResilientFlusher` 重试+本地兜底；`LogCollectLifecycle` 优雅停机强制刷写 |
| 并发与性能 | 缓冲区 `ConcurrentLinkedQueue + Atomic*`；flush 后二次阈值检查；`@LogCollect` Handler 解析按 `Method` 缓存并在配置刷新后失效；AGGREGATE 按 pattern 版本切批 |
| 工程一致性 | 默认 `Sanitizer/Masker` 统一在 core；新增 `BackpressureCallback` / `@LogCollectIgnore`；README 与实现参数名同步（`minLevel` / `messageSummary` / `backpressure` 等） |

**补丁记录（2026-03-02）**

| 类型 | 记录 |
|------|------|
| 问题 | GitHub Actions 执行 `.github/workflows/ci.yml` 时，`jdk8-17` 与 `spring-boot 2.7.18-3.4.1` 组合下，`DefaultLogMaskerTest.mask_multiplePatterns_allMasked`、`SecurityPipelineTest.process_contentWithPhone_masked` 失败，手机号未脱敏（`13812345678` 未替换为 `138****5678`） |
| 根因 | 内置手机号/身份证/银行卡规则使用 `\\b...\\b` 边界，在“中文紧邻数字”文本中跨 JDK 正则边界行为不一致，导致匹配漏掉 |
| 修复 | 将规则调整为显式 ASCII 边界：`(?<![0-9A-Za-z_])...(?![0-9A-Za-z_])`，确保中文上下文命中，同时避免在英文单词内部误匹配 |
| 回归验证 | 新增中文上下文身份证/银行卡用例与 ASCII 单词嵌入手机号防误匹配用例；在 JDK 8/17 + Spring Boot 2.7.18/3.0.13/3.2.5/3.4.1 相关测试通过 |

### 依赖范围控制原则

| 模块 | 外部依赖 | scope |
|------|---------|-------|
| `logcollect-api` | **无任何外部依赖** | - |
| `logcollect-core` | SLF4J, Context Propagation, Micrometer, Spring Context | `compile + optional` |
| `logcollect-logback-adapter` | Logback Classic | `compile + optional` |
| `logcollect-log4j2-adapter` | Log4j2 Core | `compile + optional` |
| `logcollect-spring-boot-starter` | 传递 `api/core/autoconfigure`，默认传递 Logback 适配器；Log4j2 适配器按需引入 | `compile` |
| `logcollect-config-nacos` | Nacos Client | `compile + optional` |

> 用户仅需引入 `logcollect-spring-boot-starter` + 按需引入 `logcollect-config-*`。  
> 如项目使用 Log4j2，请额外引入 `logcollect-log4j2-adapter`。

---

## 十六、生产部署指南

### 16.1 灰度发布策略

```
第一阶段（1 周）：非核心定时任务
  • 选择 1~2 个低频定时任务，使用默认聚合模式
  • 默认配置，观察 Metrics
  • 重点监控：缓冲区水位、入库耗时、内存占用

第二阶段（2 周）：核心批处理任务
  • 推广到对账、数据同步等核心批处理
  • 保守配置：maxBufferSize=50, maxBufferBytes=512KB
  • 重点监控：熔断状态、降级次数、日志丢弃数

第三阶段（1 周）：在线核心接口
  • 支付/订单等：async=false, level=WARN, collectMode=SINGLE
  • 重点监控：Handler 超时次数、业务接口 RT 变化
  • 压测验证：对比开启/关闭框架的性能差异

第四阶段：全面推广
  • 建立监控大盘
  • 配置告警规则
  • 编写应急预案
```

### 16.2 推荐初始配置

```yaml
logcollect:
  global:
    enabled: true
    async: true
    level: INFO
    collect-mode: AGGREGATE
    buffer:
      enabled: true
      max-size: 50
      max-bytes: 512KB
      total-max-bytes: 50MB
    degrade:
      enabled: true
      fail-threshold: 10
      storage: FILE
      file:
        max-total-size: 500MB
        ttl-days: 90
    security:
      sanitize:
        enabled: true
      mask:
        enabled: true
    handler-timeout-ms: 5000
    max-nesting-depth: 10
    metrics:
      enabled: true
  internal:
    log-level: INFO
  config:
    local-cache:
      enabled: true
```

### 16.3 监控重点

| 指标 | 告警阈值 | 说明 |
|------|---------|------|
| `logcollect_circuit_state > 0` | 持续 1 分钟 | 有熔断器打开 |
| `rate(logcollect_discarded_total[5m]) > 10` | 持续 2 分钟 | 日志大量丢弃 |
| `logcollect_buffer_global_utilization > 0.8` | 持续 3 分钟 | 全局缓冲区即将满 |
| `logcollect_degrade_file_disk_free_bytes < 1GB` | 持续 5 分钟 | 降级文件磁盘空间不足 |
| `logcollect_handler_timeout_total` 增长 | - | Handler 执行超时 |

### 16.4 应急预案

| 故障场景 | 应急操作 |
|---------|---------|
| **框架影响业务 RT** | ① `PUT /actuator/logcollect/enabled?value=false` 一键关闭<br>② 或推送 `logcollect.global.enabled=false` |
| **数据库被日志打垮** | ① 推送 `logcollect.global.level=ERROR` 仅收集 ERROR<br>② 切换为聚合模式减少 DB 操作 |
| **内存持续增长** | ① 降低 `buffer.total-max-bytes`<br>② 降低 `buffer.max-size` / `buffer.max-bytes`<br>③ 紧急关闭 `enabled=false` |
| **磁盘被降级文件占满** | ① `POST /actuator/logcollect/cleanupDegradeFiles?force=true`<br>② 切换 `degrade.storage=DISCARD_NON_ERROR` |
| **配置中心全部宕机** | 框架自动使用本地缓存（最后一次有效配置）<br>本地缓存也无 → 使用注解值/框架默认值 |
| **熔断器误触发** | `POST /actuator/logcollect/circuitBreakerReset?method=xxx` |

---

## 十七、常见问题

### Q1: 框架是否会影响业务方法的执行和返回值？

默认不影响。框架核心路径采用异常隔离，内部失败仅记内部日志与指标。  
唯一例外是显式开启 `blockWhenDegradeFail=true` 且兜底也失败时，框架会抛出 `LogCollectDegradeException`。

### Q2: 该选择 SINGLE 还是 AGGREGATE 模式？

| 场景 | 推荐模式 | 理由 |
|------|---------|------|
| 定时任务日志聚合到一条记录 | **AGGREGATE**（默认） | 一次 UPDATE，DB 操作最少 |
| 数据导入逐条追溯 | **SINGLE** | 需要逐条入明细表 |
| 支付接口审计 | **AGGREGATE** | 日志量小，一次写入即可 |
| 高并发秒杀 | **AGGREGATE**（默认） | 最少 DB 交互，最小 RT 影响 |
| 需要按条重试失败日志 | **SINGLE** | 逐条重试更精准 |

**不确定时使用默认值（AGGREGATE），覆盖绝大多数场景。**

### Q3: 聚合模式下 flushAggregatedLog 会被调用几次？

取决于日志量与缓冲区阈值的比值。如果一次方法调用产生的日志总量未超过 `maxBufferSize`，则仅在方法结束时调用一次（`finalFlush=true`）。如果超过阈值，则中途每达到阈值触发一次（`finalFlush=false`），方法结束时再触发最后一次（`finalFlush=true`）。

```
日志量 = 250 条, maxBufferSize = 100

第1次 flush: 100条, finalFlush=false  (中途阈值触发)
第2次 flush: 100条, finalFlush=false  (中途阈值触发)
第3次 flush:  50条, finalFlush=true   (方法结束)
```

### Q4: LogCollectContext 在各个 Handler 方法中能获取什么信息？

| 信息 | `before()` | `appendLog()` / `flushAggregatedLog()` | `after()` |
|------|-----------|---------------------------------------|----------|
| traceId / methodName / methodArgs | ✅ | ✅ | ✅ |
| businessId（用户设置的） | ❌（尚未设置）→ 设置 | ✅ | ✅ |
| attributes（用户设置的） | ❌（尚未设置）→ 设置 | ✅ | ✅ |
| totalCollectedCount | 0 | 实时累加中 | ✅ 最终值 |
| flushCount | 0 | 实时累加中 | ✅ 最终值 |
| returnValue | ❌ | ❌ | ✅ |
| error | ❌ | ❌ | ✅ |
| elapsedMillis | ✅（从方法开始算） | ✅ | ✅ |

### Q5: 自调用（`this.method()`）为什么不生效？

这是 Spring AOP 的固有限制。Spring AOP 基于代理模式，同一类内部的方法调用不经过代理，因此 `@LogCollect` 注解不生效。

**解决方案**：将需要独立收集日志的方法放到不同的 Spring Bean 中。

### Q6: 子线程日志没有被收集怎么排查？

1. 使用诊断工具：
   ```java
   log.info("上下文状态: {}", LogCollectContextUtils.diagnosticInfo());
   ```

2. 检查线程类型：
    - Spring 管理的线程池：自动传播，无需操作
    - Spring Bean 的 `ExecutorService`：自动传播（BPP 包装）
    - 手动创建的线程池：需要 `LogCollectContextUtils.wrapExecutorService()` 包装
    - `new Thread()`：需要 `LogCollectContextUtils.wrapRunnable()` 包装

3. 检查框架内部日志：将 `logcollect.internal.log-level` 设为 `DEBUG` 查看详细的上下文传播日志。

### Q7: 框架的线程安全如何保障？

| 组件 | 线程安全策略 |
|------|------------|
| `LogCollectContext` | 不可变字段 `final` + `volatile` 可变状态 + `AtomicInteger`/`AtomicLong` 计数器 + `ConcurrentHashMap` 属性 |
| `SingleModeBuffer` / `AggregateModeBuffer` | `ConcurrentLinkedQueue` + `AtomicInteger`/`AtomicLong` + CAS flush 互斥 |
| `CircuitBreaker` | `AtomicReference<State>` + CAS 操作 |
| 上下文栈 | `ThreadLocal<Deque>` 线程隔离 |
| 子线程快照 | 浅拷贝栈结构，元素引用共享但栈操作独立 |
| 脱敏规则列表 | `CopyOnWriteArrayList` |
| 配置缓存 | `ConcurrentHashMap` |

### Q8: 如何在测试中使用？

```xml
<dependency>
    <groupId>io.github.mora-na</groupId>
    <!-- <groupId>org.dpdns.mora</groupId> -->
    <artifactId>logcollect-test-support</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@SpringBootTest
class OrderServiceTest {

    @Autowired
    private InMemoryLogCollectHandler testHandler;

    @Autowired
    private OrderService orderService;

    @Test
    void testCreateOrder() {
        orderService.createOrder(new OrderRequest());

        // 验证日志被收集
        List<LogEntry> logs = testHandler.getAllLogs();
        assertFalse(logs.isEmpty());

        // 验证敏感数据已脱敏
        LogCollectAssertions.assertMasked(testHandler, traceId, "13812345678");

        // 验证无上下文泄漏
        LogCollectAssertions.assertNoContextLeak();
    }
}
```

### Q9: 非 `@LogCollect` 方法里调用静态访问器会不会报错？

不会。静态访问器全部按“静默安全”设计：

| 调用类型 | 非收集范围内行为 |
|----------|------------------|
| 读操作（如 `getCurrentTraceId` / `getCurrentAttribute`） | 返回 `null`（计数返回 `0`，布尔返回 `false`） |
| 写操作（如 `setCurrentBusinessId` / `setCurrentAttribute`） | 静默忽略，不抛异常 |
| `current()` | 返回 `null` |

---

## 十八、开发注意事项

### ⚠️ 必须遵守

| 规则 | 原因 | 违反后果 |
|------|------|---------|
| **Handler 中不要启动未包装的异步线程** | 框架只能跟踪通过工具类包装的子线程 | 未包装的子线程日志不会被收集 |
| **不要在 Handler 中执行耗时操作** | Handler 在 AOP 切面中执行（含超时保护） | 超时后 Handler 被跳过，日志可能不完整 |
| **不要在 Handler 中抛出 RuntimeException** | 框架会 catch 但会记录告警 | 日志收集流程被跳过 |
| **使用 `AbstractJdbcLogCollectHandler` 基类** | 强制参数化查询 | 自行实现可能存在 SQL 注入 |
| **生产环境启用 `transactionIsolation`（核心业务）** | 防止业务回滚导致日志丢失 | 核心操作审计日志随业务回滚丢失 |
| **SINGLE 模式建议实现 `appendLog`，AGGREGATE 模式必须实现 `flushAggregatedLog`** | 两种模式走不同方法 | 未实现 `appendLog` 会告警并丢弃单条日志 |
| **`LogCollectContext` attribute 不要存放超大对象** | attribute 生命周期等同于整次收集周期 | 内存占用抬高，可能触发 GC 压力 |
| **子线程读取静态访问器前确保上下文已传播** | 未包装线程拿不到当前上下文 | 子线程中读到 `null`，日志与业务参数关联失败 |

### 💡 最佳实践

| 实践 | 说明 |
|------|------|
| 先灰度再推广 | 在非核心任务上验证 1~2 周后再推广到核心接口 |
| 保守的初始配置 | `maxBufferSize=50`, `maxBufferBytes=512KB`, `degradeFailThreshold=10` |
| 大批量任务用聚合模式 | 万级日志聚合，DB 操作减少 99% |
| 明细追溯用单条模式 | 需逐条入库、逐条查询的场景 |
| 利用 context.setAttribute() | 跨阶段传递计算结果，避免外部变量 |
| 建立监控大盘 | 重点关注：熔断状态、缓冲区水位、日志丢弃数 |
| 准备紧急开关 | 配置中心推送 `logcollect.global.enabled=false` 或 Actuator 端点 |

### 🔧 框架内部日志排查

框架使用独立的 Logger 前缀 `com.logcollect.internal` / `io.github.morana.logcollect.internal`，并在 Appender 层硬编码排除，不会被 `@LogCollect` 拦截。

```yaml
logcollect:
  internal:
    log-level: DEBUG
```

### 📐 V1 演进原则

- 当前为首个大版本迭代阶段，README 与实现保持同频演进，优先保证正确性和一致性。
- 不引入 `@Deprecated` 或“废弃参数别名”机制；参数与接口以当前版本定义为准。
- 新增能力优先通过默认实现和配置项增强，不额外保留历史兼容分支。
- CI 持续覆盖 JDK 8/11/17/21 × Boot 2.7/3.0/3.2/3.4 × Logback/Log4j2（实际 26 组合，排除 JDK 8 + Boot 3.x 的不兼容组合）。

---

## 十九、框架定位与生态关系

```
┌──────────────────────────────────────────────────────────────────┐
│                    推荐生产架构：分层互补                          │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ @LogCollect（本框架）                                     │    │
│  │ 定位：业务日志聚合的最后一公里                              │    │
│  │ 职责：核心业务操作日志 → 精确聚合 → 业务数据库              │    │
│  │ 场景：对账任务 / 支付审计 / 订单追踪 / 数据导入             │    │
│  └────────────────────────────┬─────────────────────────────┘    │
│                               │                                  │
│  ┌────────────────────────────▼─────────────────────────────┐    │
│  │ ELK / Loki（通用日志平台）                                │    │
│  │ 职责：全量应用日志 → 全文检索 → 监控告警                   │    │
│  └────────────────────────────┬─────────────────────────────┘    │
│                               │                                  │
│  ┌────────────────────────────▼─────────────────────────────┐    │
│  │ SkyWalking / OpenTelemetry（分布式追踪）                   │    │
│  │ 职责：跨服务链路追踪 → 性能分析                            │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                  │
│  三者互补，各司其职，可共享 traceId 实现关联查询。                │
└──────────────────────────────────────────────────────────────────┘
```

### 对比

| 维度 | @LogCollect | ELK | SkyWalking |
|------|------------|-----|-----------|
| 接入成本 | ⭐⭐⭐⭐⭐ 一个注解 | ⭐⭐ 集群部署 | ⭐⭐⭐ Agent |
| 业务绑定 | ⭐⭐⭐⭐⭐ 方法级聚合 | ⭐⭐ 文本搜索 | ⭐⭐⭐ Span 关联 |
| 分布式追踪 | ⭐ 仅单 JVM | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 全文检索 | ⭐ SQL 查询 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| 运维复杂度 | ⭐⭐⭐⭐⭐ 零基础设施 | ⭐ ES 集群 | ⭐⭐⭐ |
| 降级兜底 | ⭐⭐⭐⭐⭐ 4 层降级 | ⭐⭐⭐ | ⭐⭐⭐ |

---

## 二十、已知局限性

| 局限 | 说明 | 缓解方案 |
|------|------|---------|
| **仅限单 JVM** | 不支持跨服务分布式链路追踪 | 配合 SkyWalking 共享 traceId |
| **Spring AOP 盲区** | 自调用 / `private` / `static` 方法不生效 | 将方法放到不同 Bean 中 |
| **存储能力有限** | 关系型数据库无全文检索 | 业务表可同步到 ES 做分析 |
| **非 Java 不支持** | 纯 Java 方案 | 多语言场景使用 OpenTelemetry |
| **结构化 JSON 聚合未内置** | 当前聚合内容默认文本拼接（TEXT） | V2 计划提供 JSON/CUSTOM 聚合格式 |
| **手动线程池需一行代码** | 非 Spring 管理的线程池需工具类包装 | `LogCollectContextUtils` 极简 API |
| **GraalVM Native Image** | 重度反射 + 动态代理，暂不支持 | 未来版本通过 GraalVM Metadata 支持 |

---

## 二十一、贡献指南

### 构建

```bash
# 要求 JDK 8+, Maven 3.8+
mvn clean install -DskipTests

# 完整测试（JDK 8 环境）
mvn clean verify

# 指定 Spring Boot 版本测试
mvn clean verify -Dspring-boot.version=3.2.5

# 覆盖率门禁（含 JaCoCo check，模块内单一注入链）
mvn verify \
  -pl logcollect-api,logcollect-core,logcollect-logback-adapter,logcollect-log4j2-adapter,logcollect-spring-boot-autoconfigure \
  -am
```

### 测试矩阵

```
JDK:           8 / 11 / 17 / 21
Spring Boot:   2.7.x / 3.0.x / 3.2.x / 3.4.x
Log Framework: Logback / Log4j2
组合:          实际 26 种组合（排除 JDK 8 + Boot 3.x 不兼容组合）
```

CI 工作流：`.github/workflows/ci.yml`

### 代码规范

- 所有公开 API 提供完整 Javadoc
- 边界层（AOP 切面、Appender 入口、Handler 回调包装）优先 `catch (Exception)`，并单独 `catch (Error)` 做熔断/关停后重新抛出
- 内部实现层优先使用 `catch (Exception)` 或具体异常类型，让 `Error` 向边界层汇聚
- 新增接口方法必须提供 `default` 实现
- 新增注解参数必须有默认值
- 核心模块测试覆盖率门禁：`logcollect-core` 行覆盖率 ≥ 80%

### 测试覆盖率整改路线（core）

| 模块 | 当前覆盖率（基线） | 目标覆盖率 | 优先级 |
|------|------------------|------------|--------|
| `logcollect-core` | 20.69% | ≥ 80% | 🔴 核心 |
| `logcollect-api` | 待持续提升 | ≥ 60% | 🟡 次要 |
| `logcollect-logback-adapter` | 运行时依赖强 | Appender 类级 ≥ 70% | 🟡 次要 |
| `logcollect-log4j2-adapter` | 运行时依赖强 | Appender 类级 ≥ 70% | 🟡 次要 |
| `logcollect-spring-boot-autoconfigure` | 条件装配类为主 | ≥ 30% | 🟢 辅助 |

#### 执行优先级（投入产出）

| 优先级 | 聚焦包/模块 | 目标 |
|------|-------------|------|
| P0 | `context` + `security` | 先补安全关键与差距最大路径 |
| P1 | `buffer` + `degrade` | 补齐可靠性主路径与并发分支 |
| P2 | `pipeline` + `circuitbreaker` + `config` + `api` + `mdc` | 补足中等差距模块 |
| P3 | adapters + `autoconfigure` | 完成适配器安全路径与自动装配补漏 |

#### 本轮已补齐的测试基建与重点用例

- 新增 `CoreUnitTestBase` 与 `ConcurrentTestHelper`（线程安全与上下文清理验证）
- 安全链路：`DefaultLogSanitizer` / `DefaultLogMasker` / `RegexSafetyValidator` / `StringLengthGuard` / `SecurityPipeline`
- 缓冲与可靠性：`AggregateModeBuffer` / `SingleModeBuffer` / `BoundedBufferPolicy` / `GlobalBufferMemoryManager` / `LogCollectCircuitBreaker`
- 降级与上下文：`DegradeFileManager` / `LogCollectContextManager` / `LogCollectContextUtils`
- 配置与格式：`LogCollectConfigResolver` / `PatternCleaner` / `PatternValidator` / `LogLinePatternParser`
- 跨模块补漏：`LogEntry` / `AggregatedLog` / `LogCollectContext`、Logback/Log4j2 安全路径、AOP 关键集成路径

#### JaCoCo 门禁

- `logcollect-core/pom.xml` 已内置 `jacoco-maven-plugin`（`prepare-agent`/`report`/`check`）
- 门禁规则：
  - 模块整体：`BUNDLE LINE >= 0.80`
  - 包级：`security >= 95%`，`pipeline >= 90%`
  - 包级：`buffer >= 85%`，`circuitbreaker >= 85%`
  - 包级：`context >= 85%`，`degrade >= 80%`
  - 包级：`config >= 75%`，`mdc >= 50%`
  - 包级：`format >= 70%`，`internal >= 50%`
- 模块规则：
  - `logcollect-api`：`BUNDLE LINE >= 0.60`
  - `logcollect-spring-boot-autoconfigure`：`BUNDLE LINE >= 0.30`
- 适配器规则：
  - `LogCollectLogbackAppender` 类级行覆盖率 `>= 0.70`
  - `LogCollectLog4j2Appender` 类级行覆盖率 `>= 0.70`
- 排除项：`internal/LogCollectInternalLogger`

#### CI 覆盖率步骤

- `Run tests with coverage`: `mvn -B clean verify`（多模块；不启用父 `coverage profile`，避免重复 `prepare-agent` 注入）
- `Check coverage threshold`: 读取各模块 `jacoco.xml`，执行模块级 + core 包级 + adapter 类级门禁校验
- `Upload coverage report`: 上传 `**/target/site/jacoco/` 产物

#### 提交前自查（测试）

```
□ 测试命名遵循 method_scenario_expected
□ 正常路径 + 异常路径 + 边界值已覆盖
□ null 输入已覆盖
□ 并发场景（buffer/context/circuitbreaker）已覆盖
□ 安全断言使用 doesNotContain 验证危险内容已清理
□ 文件测试使用临时目录，避免污染运行环境
□ 测试间无 ThreadLocal 泄漏
□ 每个测试方法可独立运行，不依赖执行顺序
□ 提交前执行：mvn verify -pl logcollect-core -am
```

---

## 二十二、代码规模统计（自动生成）

### 代码规模快照（自动统计）

- 统计时间：`2026-03-03 00:42:44`
- 统计脚本：`python3 scripts/analyze_code_scale.py`

| 维度 | 数值 |
|---|---:|
| Maven 模块数（含父工程） | 19 |
| Maven 子模块数 | 18 |
| 文件总数（排除构建产物目录） | 245 |
| 文本源码/配置文件数 | 232 |
| Java 文件总数 | 209 |
| 生产 Java 文件数 | 140 |
| 测试 Java 文件数 | 69 |
| 类型声明总数（class/interface/enum/record/@interface） | 285 |
| 生产类型声明数 | 185 |
| 测试源码类型声明数 | 100 |
| 测试类数（含 @Test 或命名约定） | 64 |
| 测试方法数（@Test 系列注解） | 355 |
| 包数量 | 42 |
| Java 总行数 | 23710 |
| Java 代码行 | 18812 |
| Java 注释行 | 1739 |
| Java 空行 | 3159 |
| main Java 代码行 | 12068 |
| test Java 代码行 | 6744 |
| 文本文件总行数 | 28701 |
| 文本文件非空行 | 24836 |

**Java 代码行 Top 模块**

| 排名 | 模块 | Java 代码行 |
|---:|---|---:|
| 1 | `logcollect-core` | 9627 |
| 2 | `logcollect-spring-boot-autoconfigure` | 4443 |
| 3 | `logcollect-api` | 1757 |
| 4 | `logcollect-logback-adapter` | 1171 |
| 5 | `logcollect-log4j2-adapter` | 1166 |
| 6 | `logcollect-config-nacos` | 236 |
| 7 | `logcollect-config-apollo` | 176 |
| 8 | `logcollect-config-spring-cloud` | 147 |

**主流文件类型分布 Top 8**

| 排名 | 扩展名 | 文件数 |
|---:|---|---:|
| 1 | `.java` | 209 |
| 2 | `.xml` | 19 |
| 3 | `.md` | 1 |
| 4 | `.properties` | 1 |
| 5 | `.py` | 1 |
| 6 | `.yml` | 1 |

---

## License

Apache License 2.0
