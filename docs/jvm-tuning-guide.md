# @LogCollect JVM 调优指南（V2.1）

## 1. 适用范围

- 运行环境：JDK 11 / 17（G1 GC）。
- 目标：降低日志高峰期 GC 抖动，稳定 e2e 吞吐与延迟。
- 前提：已升级到 V2.1（RingBuffer + Consumer 复用链路）。V2 架构参数见第 5 节。

## 2. 背景

V2.1 已通过预分配与复用显著降低逐事件分配，但在以下场景仍可能出现 GC 波动：

- 日志内容本身较大（格式化字符串不可避免分配）；
- 业务存在大量短周期 burst（瞬时峰值远高于稳态）；
- 运行参数对 Young 区过于保守，导致晋升过早。

## 3. 推荐参数（JDK17 + G1）

```bash
# 堆大小建议固定，避免运行期扩缩容抖动
-Xms2g
-Xmx2g
-XX:+UseG1GC

# 扩大 Young 区下限，降低短生命周期对象过早晋升
-XX:G1NewSizePercent=30

# 允许对象在 Young 区存活更多轮次再晋升
-XX:MaxTenuringThreshold=10
```

说明：

- `G1NewSizePercent=30`：将默认较小的 Young 下限抬高，更适合日志 burst。
- `MaxTenuringThreshold=10`：在高并发日志链路中，通常比默认自适应更稳定。

## 4. 验证步骤

1. 以业务等价流量运行压测（建议至少 5 分钟稳态）。
2. 对比以下指标（调参前后）：
   - Young GC 次数 / 秒
   - Mixed/Old GC 次数
   - p99 日志写入延迟（或 e2e 场景延迟）
   - `logcollect.pipeline.queue.utilization` 与 backpressure 指标
3. 若 GC 次数下降但延迟上升，优先检查 Handler I/O，而不是继续增大 Young 区。

## 5. V2（未升级）临时缓解参数

若仍在 V2（存在更多逐事件分配），可额外尝试：

```bash
-XX:G1MaxNewSizePercent=60
```

该参数在 V2.1 通常不是必需项，仅用于旧架构过渡期。

## 6. 与框架配置联动建议

- `logcollect.global.pipeline.ring-buffer-capacity`：先按压测峰值调到可接受 backpressure，再做 JVM 调参。
- `logcollect.global.pipeline.consumer-idle-strategy`：默认 `PARK`；若追求更低空闲唤醒延迟，可评估 `YIELD/SPIN`（CPU 成本更高）。
- `logcollect.global.buffer.memory-sync-threshold-bytes`：默认 `4096`，一般无需调整。

## 7. 不建议

- 不建议在未做压测回归的情况下盲目增大堆或关闭 G1 自适应。
- 不建议把日志框架性能问题全部归因 JVM；`Handler` 持久化路径通常才是主要瓶颈。
