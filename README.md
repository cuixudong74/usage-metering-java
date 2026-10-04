# usage-metering-java

用量计量与配额管控链路：采集租户上报的用量事件，按时间窗口聚合成可查询的计量结果，
并在接近或超出配额时给出可解释的判定。计量输入通过本地 HTTP 接口或测试注入，
不依赖任何外部服务（存储为进程内 + 本地文件快照）。

## 构建与运行

```bash
mvn test          # 运行全部自动化测试
mvn spring-boot:run
```

## 计量语义

### 事件模型

`UsageEvent(eventId, tenantId, dimension, quantity, eventTime)`

- `eventId` 全局唯一，是幂等去重的依据；
- `eventTime` 是事件实际发生时间，**窗口归属只由它决定**，与到达顺序无关。

### 幂等与乱序

- 同一 `eventId` 重复提交：第一次计入，后续返回 `DUPLICATE`，不重复计入；
- 乱序到达：聚合结果与按事件时间顺序处理完全一致（窗口归属与顺序无关）。

### 窗口聚合与迟到事件

- 窗口按 `metering.window-size` 对齐 epoch 切分；
- 窗口在 `窗口结束 + metering.grace-period` 后视为关闭（也可由保留策略
  `metering.retention` 周期任务关闭）；
- 窗口关闭后到达的事件按 `metering.late-policy` 处理并留下可追溯标记：
  - `ACCEPT_AND_MARK`：仍计入原窗口，状态 `LATE_ACCEPTED`，窗口的
    `lateEventCount` 递增；
  - `REJECT_AND_MARK`：拒绝计入，状态 `LATE_REJECTED`；
- 每个事件（含重复、迟到、被拒）都有处理痕迹，可经 `MeteringStore.traceOf(eventId)`
  或摄入结果中的 `traceId` 追溯。

### 并发语义

- 按租户分段读写锁：同一租户写入互斥（无丢失更新），查询持读锁返回一致性快照
  （不会读到中间状态）；不同租户并行；
- 配额规则热更新通过 `AtomicReference` 整体替换：一次判定要么读到旧版本、
  要么读到新版本，不会读到替换中间态；已返回的判定是不可变记录，**不会回退**。

### 配额判定

`QuotaRule(ruleId, tenantId, dimension, period, limit, warnThreshold, blockOnExceed)`

- `tenantId` / `dimension` 支持 `"*"` 通配；
- 统计周期按 `period` 对齐 epoch，周期内已用量来自窗口聚合；
- 判定结果 `QuotaDecision` 给出每条命中规则的限额、已用、剩余、是否超限、
  是否触及告警阈值；任一 `blockOnExceed=true` 的规则超限则 `allowed=false`；
- 规则通过 `PUT /api/quota/rules` 整体替换，运行中即时生效。

### 重启恢复

- 周期任务（`metering.snapshot-interval-ms`）与停机前把窗口聚合 + 事件痕迹
  原子落盘（临时文件 + rename）到 `metering.snapshot-dir`；
- 启动时恢复快照：计量结果、去重状态、处理痕迹一并恢复，配额消耗自洽；
- 快照之后、宕机之前的在途状态被**丢弃**，可用回放接口重放该时段历史事件重建，
  差异会以 `MISSING_EVENT` 分类呈现；
- 若 `metering.window-size` 配置变更，旧快照不可解释，启动时丢弃并告警。

### 批量回放与对账

`POST /api/replay` 接收一批历史事件，在临时存储中按事件时间重算（只读，
不修改线上状态），与线上存储逐窗口对比，差异分类：

| 分类 | 含义 | 典型原因 |
| --- | --- | --- |
| `MISSING_EVENT` | 重算值 > 线上值（线上少计） | 迟到被拒、重启丢失在途事件 |
| `VALUE_MISMATCH` | 重算值 < 线上值（线上多计） | 脏数据、重复计入 |
| `NONE` | 一致 | — |

对比范围限定在批次覆盖的 (租户, 维度) 组合内。

## 本地接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/metering/events` | 上报用量事件（幂等） |
| GET | `/api/metering/windows?tenantId=&dimension=&from=&to=` | 查询与区间重叠的窗口聚合 |
| GET | `/api/metering/trace/{eventId}` | 查询事件处理痕迹（重复/迟到/被拒） |
| GET | `/api/quota/evaluate?tenantId=&dimension=&quantity=` | 配额判定（含命中规则与剩余额度） |
| GET | `/api/quota/rules` | 查看当前配额规则 |
| PUT | `/api/quota/rules` | 整体替换配额规则（热更新） |
| POST | `/api/replay` | 批量回放与对账 |

## 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `metering.window-size` | `PT1H` | 聚合窗口大小（ISO-8601 时长） |
| `metering.late-policy` | `ACCEPT_AND_MARK` | 迟到事件策略 |
| `metering.grace-period` | `PT1H` | 窗口关闭宽限期 |
| `metering.retention` | `PT24H` | 窗口保留时长，过期窗口被周期任务关闭 |
| `metering.snapshot-dir` | `./data/snapshots` | 快照目录 |
| `metering.snapshot-interval-ms` | `60000` | 快照落盘间隔（毫秒） |

## 测试覆盖

- `MeteringStoreTest`：重复、乱序、迟到（两种策略）、窗口关闭；
- `MeteringStoreConcurrencyTest`：并发上报无丢失更新、并发查询无中间状态、
  并发重复提交只计一次；
- `QuotaServiceTest`：多规则命中与剩余额度、超限拦截、告警阈值、
  阈值热更新生效且已给判定不回退、周期隔离；
- `SnapshotStoreTest`：重启恢复（聚合 + 去重 + 痕迹）、恢复后配额自洽、
  未落盘状态丢弃并可回放重建、窗口配置变更丢弃旧快照；
- `ReplayServiceTest`：对账无差异、少计/多计分类、回放只读且顺序无关、
  对比范围限定；
- `MeteringApiIntegrationTest`：HTTP 端到端（上报 → 查询 → 配额 → 热更新 → 回放）。
