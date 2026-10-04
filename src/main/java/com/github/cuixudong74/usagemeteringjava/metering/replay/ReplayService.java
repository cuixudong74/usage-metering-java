package com.github.cuixudong74.usagemeteringjava.metering.replay;

import com.github.cuixudong74.usagemeteringjava.metering.model.LateEventPolicy;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.model.WindowAggregate;
import com.github.cuixudong74.usagemeteringjava.metering.model.WindowKey;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayReport.DiffCategory;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayReport.WindowDiff;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 批量回放与对账。
 *
 * 语义：用一批历史事件在一个全新的临时存储中按事件时间重算计量结果
 * （不修改线上存储，回放是只读的），再与线上存储逐窗口对比：
 * - 重算值大于线上值：线上少计，分类为 MISSING_EVENT（如迟到被拒、重启丢失）；
 * - 重算值小于线上值：线上多计，分类为 VALUE_MISMATCH（如重复计入）；
 * - 相等：无差异，不列入明细。
 * 对比范围限定在批次覆盖的 (租户, 维度) 组合内，避免误报无关窗口。
 */
public class ReplayService {

    private final MeteringStore store;

    public ReplayService(MeteringStore store) {
        this.store = store;
    }

    /** 回放一批事件（内部按事件时间排序后重算），返回对账报告。 */
    public ReplayReport replay(List<UsageEvent> events) {
        List<UsageEvent> sorted = events.stream()
                .sorted(Comparator.comparing(UsageEvent::eventTime)
                        .thenComparing(UsageEvent::eventId))
                .toList();

        // 在临时存储中重算：宽限期设为极大，保证历史窗口不会被关闭
        MeteringStore recomputed = new MeteringStore(store.windowSize(),
                LateEventPolicy.ACCEPT_AND_MARK);
        recomputed.setGracePeriod(Duration.ofDays(365000));
        for (UsageEvent e : sorted) {
            recomputed.ingest(e, Instant.MIN);
        }

        // 批次覆盖的 (租户, 维度) 组合
        Set<String> scope = new HashSet<>();
        for (UsageEvent e : sorted) {
            scope.add(e.tenantId() + " " + e.dimension());
        }

        Map<WindowKey, Long> before = aggregateInScope(store.snapshotAggregates(), scope);
        Map<WindowKey, Long> after = aggregateInScope(recomputed.snapshotAggregates(), scope);

        List<WindowDiff> diffs = new ArrayList<>();
        Map<DiffCategory, Long> byCategory = new EnumMap<>(DiffCategory.class);
        Set<WindowKey> allKeys = new TreeSet<>(Comparator.comparing(WindowKey::tenantId)
                .thenComparing(WindowKey::dimension).thenComparing(WindowKey::windowStart));
        allKeys.addAll(before.keySet());
        allKeys.addAll(after.keySet());
        for (WindowKey key : allKeys) {
            long b = before.getOrDefault(key, 0L);
            long a = after.getOrDefault(key, 0L);
            DiffCategory category;
            if (a > b) {
                category = DiffCategory.MISSING_EVENT;
            } else if (a < b) {
                category = DiffCategory.VALUE_MISMATCH;
            } else {
                category = DiffCategory.NONE;
            }
            byCategory.merge(category, 1L, Long::sum);
            if (category != DiffCategory.NONE) {
                diffs.add(new WindowDiff(key.tenantId(), key.dimension(),
                        key.windowStart().toString(), b, a, category));
            }
        }
        return new ReplayReport(sorted.size(), List.copyOf(diffs), byCategory);
    }

    private static Map<WindowKey, Long> aggregateInScope(List<WindowAggregate> aggregates,
                                                         Set<String> scope) {
        Map<WindowKey, Long> map = new HashMap<>();
        for (WindowAggregate a : aggregates) {
            if (scope.contains(a.key().tenantId() + " " + a.key().dimension())) {
                map.put(a.key(), a.total());
            }
        }
        return map;
    }
}
