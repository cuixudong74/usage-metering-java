package com.example.metering.core;

import com.example.metering.config.MeteringProperties;
import com.example.metering.model.LateEventRecord;
import com.example.metering.model.QuotaDecision;
import com.example.metering.model.QuotaPolicy;
import com.example.metering.model.ReconcileReport;
import com.example.metering.model.UsageEvent;
import com.example.metering.model.WindowAggregate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 计量服务门面：事件上报、窗口查询、配额判定、回放对账、重启恢复。
 *
 * <p>并发语义：按 tenantId 分条加锁（striped lock）。同一租户的上报、查询、
 * 配额判定互斥执行，不会丢失更新，也不会读到中间状态；不同租户可并行。
 * 对账需要全局一致视图，按固定顺序获取全部条锁，避免死锁。
 *
 * <p>恢复语义：上报时先写日志再应用到内存聚合；重启后按日志顺序回放，
 * 水位线与迟到判定与故障前一致，未落盘的事件视为未发生（可解释地丢弃）。
 */
public class MeteringService {

    private static final int STRIPE_COUNT = 64;

    private final WindowAggregator aggregator;
    private final QuotaEngine quotaEngine;
    private final EventJournal journal;
    private final MeteringProperties properties;
    private final ReentrantLock[] stripes;

    public MeteringService(MeteringProperties properties, EventJournal journal) {
        this.properties = properties;
        this.journal = journal;
        this.aggregator = new WindowAggregator(
                properties.getWindowSize().toMillis(),
                properties.getAllowedLateness().toMillis(),
                properties.getLateEventPolicy());
        this.quotaEngine = new QuotaEngine();
        this.quotaEngine.setUsageLookup(aggregator::usageBetween);
        this.stripes = new ReentrantLock[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new ReentrantLock();
        }
        recover();
    }

    /** 重启恢复：按日志顺序回放，重建聚合与水位线。 */
    private void recover() {
        List<UsageEvent> persisted = journal.readAll();
        for (UsageEvent e : persisted) {
            aggregator.ingest(e);
        }
    }

    private ReentrantLock stripeFor(String tenantId) {
        return stripes[Math.floorMod(tenantId.hashCode(), STRIPE_COUNT)];
    }

    private <T> T withLock(String tenantId, Supplier<T> action) {
        ReentrantLock lock = stripeFor(tenantId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 上报事件：先落盘后应用，同一租户串行。 */
    public WindowAggregator.IngestResult report(UsageEvent event) {
        return withLock(event.tenantId(), () -> {
            if (aggregator.contains(event.eventId())) {
                // 重复提交：不重复写日志、不重复计入
                return WindowAggregator.IngestResult.DUPLICATE;
            }
            journal.append(event);
            return aggregator.ingest(event);
        });
    }

    /** 查询租户某指标的窗口聚合快照。 */
    public List<WindowAggregate> query(String tenantId, String metric) {
        return withLock(tenantId, () -> aggregator.aggregates(tenantId, metric));
    }

    /** 配额判定：在同一租户锁内读取用量快照，判定结果原子反映当前状态。 */
    public QuotaDecision checkQuota(String tenantId, String metric, long requested) {
        return withLock(tenantId,
                () -> quotaEngine.evaluate(tenantId, metric, requested, Instant.now()));
    }

    /** 运行中更新配额策略（阈值/限额/策略），仅影响后续判定。 */
    public void upsertPolicy(QuotaPolicy policy) {
        quotaEngine.upsertPolicy(policy);
    }

    public List<QuotaPolicy> policies() {
        return quotaEngine.policies();
    }

    public List<QuotaDecision> decisionLog() {
        return quotaEngine.decisionLog();
    }

    public List<LateEventRecord> lateEvents(String tenantId) {
        return withLock(tenantId, () -> aggregator.lateEvents().stream()
                .filter(r -> r.event().tenantId().equals(tenantId))
                .toList());
    }

    /**
     * 回放对账：用一批历史事件在独立聚合器上重算，与当前状态逐窗口对比，
     * 差异按类型分类（重复、迟到、缺失、数量不符、多出）。
     */
    public ReconcileReport reconcile(List<UsageEvent> historicalBatch) {
        lockAll();
        try {
            // 1. 独立重放，记录每个事件的重放结果
            WindowAggregator replay = new WindowAggregator(
                    properties.getWindowSize().toMillis(),
                    properties.getAllowedLateness().toMillis(),
                    properties.getLateEventPolicy());
            List<ReconcileReport.DiffEntry> diffs = new ArrayList<>();
            for (UsageEvent e : historicalBatch) {
                WindowAggregator.IngestResult r = replay.ingest(e);
                switch (r) {
                    case DUPLICATE -> diffs.add(new ReconcileReport.DiffEntry(
                            ReconcileReport.DiffType.DUPLICATE_IGNORED, e.eventId(),
                            "duplicate event within batch, counted once"));
                    case LATE_REJECTED -> diffs.add(new ReconcileReport.DiffEntry(
                            ReconcileReport.DiffType.LATE_TO_CLOSED_WINDOW, e.eventId(),
                            "event arrived after its window closed, rejected by policy"));
                    case LATE_ACCUMULATED -> diffs.add(new ReconcileReport.DiffEntry(
                            ReconcileReport.DiffType.LATE_TO_CLOSED_WINDOW, e.eventId(),
                            "event arrived after its window closed, accumulated by policy"));
                    default -> {
                    }
                }
            }

            // 2. 按 (tenant, metric) 逐窗口对比重算结果与当前状态
            Set<String> pairs = new LinkedHashSet<>();
            for (UsageEvent e : historicalBatch) {
                pairs.add(e.tenantId() + "|" + e.metric());
            }
            for (String pair : pairs) {
                String tenantId = pair.substring(0, pair.indexOf('|'));
                String metric = pair.substring(pair.indexOf('|') + 1);
                Map<Long, WindowAggregate> replayed = byWindowStart(replay.aggregates(tenantId, metric));
                Map<Long, WindowAggregate> current = byWindowStart(aggregator.aggregates(tenantId, metric));
                for (Map.Entry<Long, WindowAggregate> e : replayed.entrySet()) {
                    WindowAggregate cur = current.get(e.getKey());
                    if (cur == null) {
                        diffs.add(new ReconcileReport.DiffEntry(
                                ReconcileReport.DiffType.MISSING_IN_CURRENT, null,
                                String.format("window %s/%s@%d replayed=%d missing in current",
                                        tenantId, metric, e.getKey(), e.getValue().totalQuantity())));
                    } else if (cur.totalQuantity() != e.getValue().totalQuantity()) {
                        diffs.add(new ReconcileReport.DiffEntry(
                                ReconcileReport.DiffType.QUANTITY_MISMATCH, null,
                                String.format("window %s/%s@%d replayed=%d current=%d",
                                        tenantId, metric, e.getKey(),
                                        e.getValue().totalQuantity(), cur.totalQuantity())));
                    }
                }
                for (Map.Entry<Long, WindowAggregate> e : current.entrySet()) {
                    if (!replayed.containsKey(e.getKey())) {
                        diffs.add(new ReconcileReport.DiffEntry(
                                ReconcileReport.DiffType.EXTRA_IN_CURRENT, null,
                                String.format("window %s/%s@%d current=%d not in replay batch",
                                        tenantId, metric, e.getKey(), e.getValue().totalQuantity())));
                    }
                }
            }
            return new ReconcileReport(historicalBatch.size(), diffs.size(), List.copyOf(diffs));
        } finally {
            unlockAll();
        }
    }

    private Map<Long, WindowAggregate> byWindowStart(List<WindowAggregate> aggregates) {
        Map<Long, WindowAggregate> map = new TreeMap<>();
        for (WindowAggregate a : aggregates) {
            map.put(a.windowStartEpochMs(), a);
        }
        return map;
    }

    private void lockAll() {
        for (ReentrantLock lock : stripes) {
            lock.lock();
        }
    }

    private void unlockAll() {
        for (int i = stripes.length - 1; i >= 0; i--) {
            stripes[i].unlock();
        }
    }
}
