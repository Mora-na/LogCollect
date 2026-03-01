# LogCollect

`@LogCollect` 是一个面向 Java/Spring 的方法级日志收集框架。

本版本 README 对应当前代码的**最终重构方案**，重点是：
- `LogEntry` 结构化重构（Builder、`throwableString`、`timestamp`）
- 默认格式化机制重构（Pattern 解析 + 控制台 Pattern 自动探测）
- 安全流水线重构（消息与异常堆栈分离净化）
- Appender 适配层重构（提取即拷贝，避免事件复用回收风险）
- 缓冲区行为收敛（AGGREGATE 入缓冲时格式化，仅存字符串）

---

## 1. 模块改动总览

```text
┌────────────────────────────────────────────────────────────────────────┐
│                          重构改动总览                                   │
│                                                                        │
│  logcollect-api (零外部依赖)                                            │
│  ├── LogEntry               去掉构造器直灌，新增 throwableString       │
│  │                          新增 timestamp，Builder 模式，估算修正      │
│  ├── LogCollectHandler      formatLogLine 默认实现重构                  │
│  │                          新增 logLinePattern()                      │
│  ├── LogLinePatternParser   新增：轻量 pattern 解析器                   │
│  ├── LogLineDefaults        新增：默认 pattern 持有与覆盖               │
│  └── LogSanitizer           新增 sanitizeThrowable 接口方法            │
│                                                                        │
│  logcollect-core                                                       │
│  ├── ConsolePatternDetector SPI接口 + PatternCleaner                   │
│  ├── DefaultLogSanitizer    实现 sanitizeThrowable                     │
│  ├── SecurityPipeline       消息与堆栈分离净化                          │
│  └── AggregateModeBuffer    入缓冲即格式化，仅存字符串                  │
│                                                                        │
│  logcollect-logback-adapter                                             │
│  ├── LogCollectLogbackAppender    提取异常堆栈 + Builder 构建           │
│  └── LogbackConsolePatternDetector  动态提取 pattern                    │
│                                                                        │
│  logcollect-log4j2-adapter                                              │
│  ├── LogCollectLog4j2Appender     安全提取 + 深拷贝(防回收)             │
│  └── Log4j2ConsolePatternDetector  动态提取 pattern                     │
│                                                                        │
│  logcollect-spring-boot-autoconfigure                                   │
│  └── ConsolePatternInitializer    启动检测并注入默认 pattern            │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 关键设计原则

- **纯数据载体**：`LogEntry` 不承担格式化、净化、过滤职责。
- **提取即释放**：Appender 中从原始日志事件提取字段后立刻释放事件引用。
- **先安全后缓冲**：安全流水线先处理，再进入缓冲。
- **模式分工明确**：
  - `SINGLE` 存 `LogEntry`，便于结构化使用。
  - `AGGREGATE` 入缓冲即 `formatLogLine`，仅存字符串，降低对象驻留。
- **保守内存估算**：统一 `40 + length*2` 的字符串估算策略，宁可高估提前 flush，不低估导致内存风险。

---

## 3. LogEntry 最终模型

路径：`logcollect-api/src/main/java/com/logcollect/api/model/LogEntry.java`

字段：
- `traceId`
- `content`
- `level`
- `time`
- `timestamp`
- `threadName`
- `loggerName`
- `throwableString`

特性：
- `final` 类 + 全字段 `final`
- `private` 构造 + `Builder`
- `hasThrowable()` 快捷判断
- `estimateBytes()` 使用保守估算

估算规则：
- `LogEntry` 自身按 `80 bytes`
- `LocalDateTime` 按 `48 bytes`
- 每个非空字符串按 `40 + length*2`

---

## 4. LogCollectHandler 格式化机制

路径：`logcollect-api/src/main/java/com/logcollect/api/handler/LogCollectHandler.java`

新增与变更：
- 新增 `logLinePattern()`
- `formatLogLine(LogEntry entry)` 默认实现改为：
  - `LogLinePatternParser.format(entry, logLinePattern())`

默认 pattern 来源优先级：
1. 配置中心覆盖：`logcollect.global.format.log-line-pattern`
2. 启动时自动探测控制台 appender pattern（并清理颜色/PID）
3. 框架兜底 pattern

### 4.1 支持占位符

- `%d{pattern}` 时间
- `%p` / `%level` 级别
- `%t` / `%thread` 线程
- `%c{n}` / `%logger{n}` Logger 缩写
- `%C` / `%loggerFull` Logger 全名
- `%m` / `%msg` 消息
- `%ex` / `%throwable` / `%wEx` 异常堆栈
- `%n` 换行

宽度修饰：
- `%-5p` 左对齐最小宽度
- `%15.15t` 最大宽度截断 + 最小宽度填充

---

## 5. Pattern 自动探测与清理

### 5.1 SPI

路径：`logcollect-core/src/main/java/com/logcollect/core/format/ConsolePatternDetector.java`

接口：
- `boolean isAvailable()`
- `String detectRawPattern()`

实现：
- `LogbackConsolePatternDetector`
- `Log4j2ConsolePatternDetector`

### 5.2 清理器

路径：`logcollect-core/src/main/java/com/logcollect/core/format/PatternCleaner.java`

清理策略：
- 去 `%clr(...)` / `%highlight(...)` / `%style(...)`
- 去 PID 占位符
- 解析 Spring 属性默认值 `${XXX:-default}`
- 去装饰性 `---`
- 压缩多空格

### 5.3 启动初始化

路径：`logcollect-spring-boot-autoconfigure/src/main/java/com/logcollect/autoconfigure/ConsolePatternInitializer.java`

流程：
1. 遍历可用 detector
2. 提取 raw pattern
3. `PatternCleaner.clean(...)`
4. 校验至少包含消息占位符
5. `LogLineDefaults.setDetectedPattern(...)`
6. 全失败则落回兜底 pattern

---

## 6. 安全流水线（消息/堆栈分离）

### 6.1 接口

路径：`logcollect-api/src/main/java/com/logcollect/api/sanitizer/LogSanitizer.java`

方法：
- `sanitize(String raw)`：消息严格净化
- `sanitizeThrowable(String throwableString)`：堆栈宽松净化（保留 `\r\n\t`）

### 6.2 默认实现

路径：`logcollect-core/src/main/java/com/logcollect/core/security/DefaultLogSanitizer.java`

- 消息：清 HTML、ANSI、控制字符（含换行/制表）
- 堆栈：清 HTML、ANSI、危险控制字符（保留换行/制表）

### 6.3 SecurityPipeline

路径：`logcollect-core/src/main/java/com/logcollect/core/pipeline/SecurityPipeline.java`

执行顺序：
1. `sanitize(content)`
2. `sanitizeThrowable(throwable)`
3. `mask(content)`
4. `mask(throwable)`
5. 构建新的安全 `LogEntry`

---

## 7. Appender 适配层（最终行为）

### 7.1 Logback

路径：`logcollect-logback-adapter/.../LogCollectLogbackAppender.java`

- 在 `append(ILoggingEvent)` 内一次性提取：
  - `traceId/content/level/time/timestamp/thread/logger/throwableString`
- 异常提取：`ThrowableProxyUtil.asString(IThrowableProxy)`
- 使用 `SecurityPipeline` 输出安全 `LogEntry`

### 7.2 Log4j2

路径：`logcollect-log4j2-adapter/.../LogCollectLog4j2Appender.java`

- 在 `append(LogEvent)` 内一次性提取并做字符串拷贝
- 关键点：避免持有 `MutableLogEvent` 的生命周期引用
- 异常提取：`Throwable#printStackTrace(PrintWriter)`
- 使用 `SecurityPipeline` 输出安全 `LogEntry`

---

## 8. 缓冲区与模式语义

### 8.1 SINGLE

- 缓冲区：`SingleModeBuffer`
- 存储对象：完整 `LogEntry`
- Flush 行为：逐条调用 `handler.appendLog(...)`

### 8.2 AGGREGATE

- 缓冲区：`AggregateModeBuffer`
- **入缓冲即格式化**：`handler.formatLogLine(entry)`
- 存储对象：`LogSegment(formattedLine, level, time, timestamp, estimatedBytes)`
- Flush 行为：拼接为 `AggregatedLog.content`，调用 `handler.flushAggregatedLog(...)`

### 8.3 估算

- AGGREGATE 单行字符串估算：`40 + length*2`
- SINGLE 估算：`entry.estimateBytes()`

---

## 9. 完整数据流（最终版）

```text
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

---

## 10. 对现有用户的影响

| 用户场景 | 影响 | 说明 |
|---|---|---|
| 仅用 AGGREGATE + `flushAggregatedLog` | ✅ 基本零改动 | `content` 仍为字符串聚合体，内容更完整（可含异常） |
| 覆写了 `formatLogLine` | ✅ 零改动 | 覆写优先级最高，不受默认 pattern 解析影响 |
| SINGLE 中读取 `entry.getContent()` | ✅ 零改动 | `content` 语义保持“消息文本” |
| 读取 `entry.getLoggerName()` | ✅ 零改动 | 仍返回全限定类名 |
| 需要异常堆栈 | ✅ 新增能力 | `entry.getThrowableString()` + `entry.hasThrowable()` |

潜在注意点：
- `LogEntry` 构造方式切换为 `Builder`。
- 自定义 `LogSanitizer` 需要实现新方法 `sanitizeThrowable(...)`。

---

## 11. 配置项（新增重点）

全局动态格式覆盖：

```properties
logcollect.global.format.log-line-pattern=%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%t] %c - %m%ex%n
```

说明：
- 为空时不覆盖，使用自动探测/兜底。
- 配置变更后会通过 `LogCollectConfigResolver#onConfigChange` 同步到 `LogLineDefaults`。

---

## 12. 快速接入

### 12.1 依赖

```xml
<dependency>
  <groupId>com.logcollect</groupId>
  <artifactId>logcollect-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

### 12.2 最小 Handler

```java
@Component
public class DemoHandler implements LogCollectHandler {
    @Override
    public void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        // 持久化 aggregatedLog.getContent()
    }
}
```

### 12.3 注解使用

```java
@LogCollect(handler = DemoHandler.class)
public void runJob() {
    // your business logic
}
```

---

## 13. 工程结构（核心）

```text
logcollect-api
logcollect-core
logcollect-logback-adapter
logcollect-log4j2-adapter
logcollect-spring-boot-autoconfigure
logcollect-spring-boot-starter
logcollect-config-* (nacos / apollo / spring-cloud)
logcollect-test-support
logcollect-samples
```

---

## 14. 构建与验证

```bash
mvn -DskipTests compile
mvn test
```

---

## 15. 版本说明

当前 README 对应重构后实现，关键代码可从以下路径直接查看：
- `logcollect-api/src/main/java/com/logcollect/api/model/LogEntry.java`
- `logcollect-api/src/main/java/com/logcollect/api/handler/LogCollectHandler.java`
- `logcollect-api/src/main/java/com/logcollect/api/format/LogLinePatternParser.java`
- `logcollect-core/src/main/java/com/logcollect/core/pipeline/SecurityPipeline.java`
- `logcollect-core/src/main/java/com/logcollect/core/format/PatternCleaner.java`
- `logcollect-logback-adapter/src/main/java/com/logcollect/logback/LogCollectLogbackAppender.java`
- `logcollect-log4j2-adapter/src/main/java/com/logcollect/log4j2/LogCollectLog4j2Appender.java`
- `logcollect-spring-boot-autoconfigure/src/main/java/com/logcollect/autoconfigure/ConsolePatternInitializer.java`

