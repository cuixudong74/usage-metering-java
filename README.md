# usage-metering-java

用量计量与配额管控链路：采集租户上报的用量事件，按时间窗口聚合成可查询的计量结果，
并在接近或超出配额时给出可解释的判定。计量输入通过本地 REST 接口或测试注入，
不依赖任何外部服务。

## 模块结构

```
com.example.metering
├── model      UsageEvent / WindowAggregate / QuotaPolicy / QuotaDecision / LateEventRecord / ReconcileReport
├── core       WindowAggregator（窗口聚合）/ QuotaEngine（配额判定）/ MeteringService（门面）
│              EventJournal（FileEventJournal 落盘 / InMemoryEventJournal 内存）
├── api        MeteringController（REST 接口）
└── config     MeteringProperties（配置项）/ MeteringConfig（装配）
```

## 计量语义

### 事件上报与幂等
- 事件模型：`UsageEvent(eventId, tenantId, metric, quantity, eventTime)`，`eventId` 全局唯一。
- 同一 `eventId` 重复提交返回 `DUPLICATE`，只计入一次，且不重复写日志。
- 事件按**自身 eventTime** 归入对齐的固定窗口，与到达顺序无关：
  乱序到达的聚合结果与按正确顺序处理完全一致。

### 时间窗口与迟到事件
- 窗口为固定滚动窗口（`metering.window-size`，默认 1 小时），按 epoch 对齐。
- 水位线：`watermark = 已见最大 eventTime - metering.allowed-lateness`；
  `windowEnd <= watermark` 的窗口视为已关闭（聚合结果中 `closed=true`）。
- 窗口关闭后到达的事件按 `metering.late-event-policy` 处理，并留下
  `LateEventRecord` 可追溯标记（事件、目标窗口、应用的策略、到达时间）：
  - `REJECT`（默认）：拒绝计入，仅留痕；
  - `ACCUMULATE`：仍计入已关闭窗口并留痕。
- 无论哪种策略，迟到的 `eventId` 都会登记，重复提交判定保持一致。

### 配额判定
- 配额按 `(tenantId, metric, period)` 组合配置，同一维度可配多个不同周期的配额，
  全部参与判定，任一 `BLOCK` 策略超限即拒绝。
- 判定结果 `QuotaDecision` 给出：命中的配额 key、当前用量、限额、剩余额度、
  是否触发告警阈值、以及逐配额的可解释 reason 串。
- 无匹配配额时默认放行并在 reason 中说明。
- 每次判定追加到判定日志（容量 10000，只增不改）。

### 并发语义
- 按 `tenantId` 分条加锁（64 条 striped lock）：同一租户的上报、查询、配额判定
  互斥执行，不丢更新、不读中间状态；不同租户并行。
- 配额判定在同一租户锁内读取用量快照，判定原子反映当前状态。
- 对账需要全局一致视图，按固定顺序获取全部条锁，避免死锁。

### 阈值变更与不回退
- `PUT /api/quota/policies` 运行中更新阈值/限额/策略，立即对后续判定生效。
- 已写入判定日志的历史判定永不修改，不会出现回退。

### 重启恢复
- 配置 `metering.journal-path` 后，事件**先落盘（JSON Lines 追加）再应用到内存聚合**；
  落盘失败则上报失败，不会出现"已计入但未落盘"。
- 重启时按日志顺序全量回放，水位线与迟到判定与故障前一致，计量结果与配额消耗自洽。
- 未配置日志路径时使用纯内存日志，重启后中间状态被（可解释地）丢弃。

### 批量回放与对账
- `POST /api/reconcile` 用一批历史事件在独立聚合器上重算，与当前状态逐窗口对比，
  差异分类：
  - `DUPLICATE_IGNORED`：批次内重复事件，只计一次；
  - `LATE_TO_CLOSED_WINDOW`：重放时落入已关闭窗口；
  - `MISSING_IN_CURRENT`：重算出的窗口在当前状态缺失；
  - `QUANTITY_MISMATCH`：同一窗口重算值与当前值不一致；
  - `EXTRA_IN_CURRENT`：当前状态存在批次之外的窗口。

## 配置项（application.properties）

| 配置 | 默认 | 说明 |
|---|---|---|
| `metering.window-size` | `1h` | 聚合窗口大小（Duration） |
| `metering.allowed-lateness` | `10m` | 水位线宽限，决定窗口何时关闭 |
| `metering.late-event-policy` | `REJECT` | 迟到事件策略：`REJECT` / `ACCUMULATE` |
| `metering.journal-path` | 空 | 事件日志文件路径；空则仅内存（重启即丢弃） |

配额策略（运行时通过接口配置）：`tenantId`、`metric`、`period`（Duration）、
`limit`、`alertThresholdRatio`（0~1）、`overLimitStrategy`（`ALERT_ONLY` / `BLOCK`）。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/events` | 上报单个事件 |
| POST | `/api/events/batch` | 批量上报 |
| GET | `/api/aggregates?tenantId&metric` | 查询窗口聚合 |
| GET | `/api/late-events?tenantId` | 查询迟到事件标记 |
| POST | `/api/quota/check?tenantId&metric&requested` | 配额判定 |
| PUT | `/api/quota/policies` | 新增/更新配额策略（运行中生效） |
| GET | `/api/quota/policies` | 列出配额策略 |
| GET | `/api/quota/decisions` | 历史判定日志 |
| POST | `/api/reconcile` | 批量回放对账 |

## 构建与测试

```bash
mvn test
```

测试覆盖：重复提交、乱序到达、迟到事件（REJECT/ACCUMULATE）、并发上报与查询
（不丢更新、不读中间状态）、阈值运行中变更（不回退）、重启恢复、回放对账分类、
REST 接口冒烟。
