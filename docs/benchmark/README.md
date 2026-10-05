# 压测留档（每版本用数据回答"上一个痛点解决了没有"）

本目录存放各版本的压测报告、JMeter 资产与原始结果。**结论优先看报告，原始数据用于复核。**

## 1. 报告索引

| 报告 | 回答的问题 | 核心结论 |
|---|---|---|
| [`V1-压测报告.md`](V1-压测报告.md) | 缓存带来多少收益？同步统计的代价有多大？ | 仅缓存：QPS **+56%**、p99 **-75%**；加上同步统计后 QPS 回落 **-36%** → 引入 MQ 的量化依据 |
| [`V2-压测报告.md`](V2-压测报告.md) | MQ 异步化到底值不值？ | 100 线程 QPS **+28%**、avg **-25%**；积压 1.6 万条消息时跳转不受影响（削峰填谷） |
| [`V3.5-发号器对比.md`](V3.5-发号器对比.md) | 四种发号方案的成本差多少？短链该选哪个？ | 号段(内存) **0.29µs** / 雪花 **0.34µs** / 号段(含 DB 摊销) **7.16µs** / Redis INCR **158µs** / DB 自增 **1393µs** → **短链短码优先号段** |

V3.1~V3.4（防穿透/防击穿/防雪崩/多级缓存）的验证以**故障演练**为主而非吞吐压测，
数据与构造方式记录在 [`../03-开发日志.md`](../03-开发日志.md) 对应章节（例如：杀掉全部 Redis 连接后 L1 仍 302/48ms；
`CLIENT PAUSE 8000` + 40 个真齐发请求 → 302=13 / 503=27；Redis `PAUSE 3s` 场景布隆过滤器 3063ms → 36ms）。

## 2. 如何复现

### 2.1 一键跑 JMeter 场景（V1/V2 的对照实验）

```powershell
# 跑单个场景（S1 基线）
powershell -NoProfile -ExecutionPolicy Bypass -File docs\benchmark\run-benchmark.ps1 `
  -Only "S1" -Threads 100 -Loops 200 -Ramp 1 -WarmThreads 20 -WarmLoops 100

# 跑全部场景（S1~S5，每个场景自动重启应用并清缓存 key）
powershell -NoProfile -ExecutionPolicy Bypass -File docs\benchmark\run-benchmark.ps1
```

脚本做四件事：**停掉 8080 上的旧进程 → 用场景参数启动应用 → JMeter 采集（强制覆盖 JTL）→ 用 `analyze-jtl.ps1` 汇总**。

场景定义（写在脚本的 `$scenarios` 里，全部靠启动参数切换，同一份 jar）：

| 场景 | 缓存 | 统计 | 统计实现 | 含义 |
|---|---|---|---|---|
| S1 | ✗ | ✗ | — | **V0 等价基线** |
| S2 | ✓ | ✗ | — | 只有缓存的收益 |
| S3 | ✓ | ✓ | 同步（V1 行为） | V1 现状 |
| S4 | ✗ | ✓ | 同步 | 只有统计的代价 |
| S5 | ✓ | ✓ | **MQ 异步（V2）** | V2 改造目标 |

关键参数：`-Threads`（线程数）、`-Loops`（每线程请求数）、`-Ramp`（ramp-up 秒）、
`-WarmThreads/-WarmLoops`（**预热**，必须做，否则 JIT 与连接池初始化会被算成服务端延迟）、
`-Code`（压测的短码）、`-Jmeter`（本机 JMeter 路径，需按环境修改）。

### 2.2 ⚠️ 复现历史口径的注意事项

当前代码已演进到 V3.5，**默认开启**了多级缓存、布隆过滤器等功能。
若要让 S1~S5 与 V1/V2 报告口径一致，需要额外关闭这些后加的特性：

```powershell
# 复现 V1/V2 口径时建议追加的开关
--shortlink.cache.local.enabled=false        # 关 L1 本地缓存（V3.3）
--shortlink.bloom.enabled=false              # 关布隆过滤器（V3.1/V3.4）
--shortlink.cache.anti-breakdown.logical-expire-enabled=false   # 关逻辑过期（V3.2）
--shortlink.cache.anti-breakdown.mutex-rebuild-enabled=false    # 关互斥重建（V3.2）
```

反之，如果想看**当前版本（V3.5）的完整能力**，直接用默认配置跑即可。
两种口径不要混在一张表里比较。

### 2.3 发号器基准（不走 JMeter）

```bash
mvn -B test "-Dtest=IdGeneratorBenchmarkTest" "-Dshortlink.benchmark=true"
```

该基准直连真实 MySQL/Redis 测"每次取号"的成本。**注意副作用**：会推进 `t_segment.max_id`（约 20 万）、
Redis 发号 key（5 万）与 `t_sequence`（约 2200 行）——号只要求唯一，不影响使用，但 id 会整体前移。

## 3. 目录结构

```
docs/benchmark/
├── README.md                  # 本文件：索引与复现说明
├── V1-压测报告.md / V2-压测报告.md / V3.5-发号器对比.md
├── redirect-benchmark.jmx     # JMeter 测试计划（跳转链路）
├── run-benchmark.ps1          # 一键跑场景（重启应用 + 采集 + 汇总）
├── analyze-jtl.ps1            # 解析 JTL 输出 QPS/avg/p50/p95/p99/max/错误率
└── results/                   # 原始结果：*.jtl（样本）+ JMeter 日志 + app.log（应用 stdout）
                               # 未纳入版本控制：单文件约 2MB、目录总计约 14MB，
                               # 报告中的结论均可在本地按上文重新跑出
```

## 4. 测量口径与局限（避免过度解读）

所有报告都遵循同一套口径，并明确写出局限：

1. **必须预热**：同一场景预热不足时 avg 可差 5 倍（JIT 编译 + 连接池初始化被算进服务端延迟）
2. **必须强制覆盖 JTL**：JMeter 默认向已存在的 JTL 追加样本，会得出"QPS=10.7"这类严重失真数据（脚本已用 `-f` 规避）
3. **客户端与服务端同机**：JMeter、应用、MySQL、Redis（以及 V2 的 Broker + 消费者）共享一台 16 核机器，
   所以**绝对 QPS 不代表服务端容量**，只能用于同环境横向对比
4. **数据量小**：`t_link` 仅几十行且全在 buffer pool 中，DB 点查成本被严重低估
5. **单热点短码**：只压一个 code，未考察命中率随短码集合变化的影响
6. **连接池未调优**：Hikari 固定 10 连接（V0 遗留），高并发下它才是真瓶颈

> 工程结论：**先保证测量方法正确，再谈优化效果**。V1 报告里"同步统计吃掉缓存收益"的结论之所以可信，
> 是因为四个场景跑的是同一份代码、同一套参数、同样的预热与清缓存流程。
