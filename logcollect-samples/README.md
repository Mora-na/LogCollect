# logcollect-samples

8 个矩阵样例模块：

- `logcollect-sample-boot27-logback`
- `logcollect-sample-boot27-log4j2`
- `logcollect-sample-boot30-logback`
- `logcollect-sample-boot30-log4j2`
- `logcollect-sample-boot32-logback`
- `logcollect-sample-boot32-log4j2`
- `logcollect-sample-boot34-logback`
- `logcollect-sample-boot34-log4j2`

每个模块都会在启动后自动执行以下场景：

- 同步方法
- Spring `@Async`（默认 AsyncConfigurer）
- Spring `@Async`（自定义 AsyncConfigurer）
- Spring `ThreadPoolTaskExecutor`
- `CompletableFuture + Spring` 池
- WebFlux `Mono`
- WebFlux `Flux`
- Spring Bean `ExecutorService`
- 手动 `ExecutorService`
- `new Thread()`
- 第三方库回调（Caffeine）
- `ForkJoinPool`
- `parallelStream`
- 嵌套 `@LogCollect`
- Servlet `AsyncContext`

所有业务日志都带中文场景编号；`SampleScenarioLogCollectHandler` 会额外打印每个 `@LogCollect` 的聚合收集结果。

## 运行

全仓一把构建（Maven 运行在 JDK 17+，Boot 2.7 sample 通过 Toolchains 选 JDK 8）：

```bash
bash scripts/build-all-with-toolchains.sh
```

一键顺序跑完 8 个模块：

```bash
bash logcollect-samples/run-sample-matrix.sh
```

只跑单个模块：

```bash
bash logcollect-samples/run-sample-matrix.sh --module logcollect-sample-boot34-log4j2
```

脚本在 macOS 下会优先通过 `/usr/libexec/java_home` 自动切换 JDK。
批量运行前会先安装当前工作区的 `starter/core/autoconfigure/adapter` 产物，确保样例使用的是最新源码而不是本地仓库里的旧版本。
如果是在 Linux 或需要显式指定本地 JDK，请先设置：

```bash
export JAVA_8_HOME=/path/to/jdk8
export JAVA_17_HOME=/path/to/jdk17

# Maven Toolchains 也兼容这些无下划线变量名
export JAVA8_HOME=/path/to/jdk8
export JAVA17_HOME=/path/to/jdk17
```

Boot 2.7 模块可在 JDK 8 下运行：

```bash
mvn -f logcollect-samples/logcollect-sample-boot27-logback/pom.xml \
  -am -DskipTests \
  org.springframework.boot:spring-boot-maven-plugin:2.7.18:run \
  -Dspring-boot.run.arguments=--sample.exit-after-run=true
```

Boot 3.x 模块需在 JDK 17+ 下运行：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"

mvn -f logcollect-samples/logcollect-sample-boot34-log4j2/pom.xml \
  -am -DskipTests \
  org.springframework.boot:spring-boot-maven-plugin:3.4.1:run \
  -Dspring-boot.run.arguments=--sample.exit-after-run=true
```

## 日志

- 控制台会输出每个场景的业务日志和 `LogCollect` 聚合结果
- 批量脚本会额外输出 `logs/logcollect-samples/<module>.console.log`
- 应用文件日志默认输出到各样例模块目录下的 `logs/logcollect-samples/<spring.application.name>.log`
- 批量执行摘要会打印每个模块对应的实际 app log 路径
- 批量执行摘要输出到 `logs/logcollect-samples/sample-batch-summary.txt`
- 批量执行 Markdown 报告输出到 `logs/logcollect-samples/sample-matrix-report.md`
- 批量执行 JSON 报告输出到 `logs/logcollect-samples/sample-matrix-report.json`
