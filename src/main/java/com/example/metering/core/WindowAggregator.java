package com.example.metering.core;

import com.example.metering.model.LateEventRecord;
import com.example.metering.model.UsageEvent;
import com.example.metering.model.WindowAggregate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 时间窗口聚合器：按事件自身时间归窗，处理乱序与迟到事件。
 *
 * <p>语义：
 * <ul>
 *   <li>幂等：同一 eventId 只计入一次，重复提交返回 DUPLICATE。</li>
 *   <li>乱序无关：事件按 eventTime 归入对齐的固定窗口，与到达顺序无关。</li>
 *   <li>水位线：watermark = 已见最大 eventTime - allowedLateness；
 *       windowEnd &lt;= watermark 的窗口视为已关闭。</li>
 *   <li>迟到事件：按 LateEventPolicy 处理，并留下 LateEventRecord 标记。</li>
 * </ul>
 * 所有公共方法由调用方在同一把锁下调用（见 MeteringService），本类不做并发控制。
 */
public class WindowAggregator {

    public enum IngestResult {
        APPLIED,
        DUPLICATE,
        LATE_REJECTED,
        LATE_ACCUMULATED
    }

    private record WindowKey(String tenantId, String metric, long windowStart) {
    }

    private static final class MutableWindow {
        long totalQuantity;
        long eventCount;
    }

    private final long windowSizeMs;
    private final long allowedLatenessMs;
    private final LateEventPolicy lateEventPolicy;

    private final Map<WindowKey, MutableWindow> windows = new HashMap<>();
    private final Set<String> appliedEventIds = new HashSet<>();
    private final List<LateEventRecord> lateEvents = new ArrayList<>();
    private long maxEventTimeMs = Long.MIN_VALUE;

    public WindowAggregator(long windowSizeMs, long allowedLatenessMs, LateEventPolicy lateEventPolicy) {
        this.windowSizeMs = windowSizeMs;
        this.allowedLatenessMs = allowedLatenessMs;
        this.lateEventPolicy = lateEventPolicy;
    }

    public IngestResult ingest(UsageEvent event) {
        return ingest(event, Instant.now());
    }

    public IngestResult ingest(UsageEvent event, Instant arrivedAt) {
        if (!appliedEventIds.add(event.eventId())) {
            return IngestResult.DUPLICATE;
        }
        long eventMs = event.eventTime().toEpochMilli();
        long windowStart = Math.floorDiv(eventMs, windowSizeMs) * windowSizeMs;
        long windowEnd = windowStart + windowSizeMs;

        if (windowEnd <= watermarkMs()) {
            // 窗口已关闭：按策略处理并留痕
            lateEvents.add(new LateEventRecord(event, windowStart, windowEnd,
                    lateEventPolicy.name(), arrivedAt));
            if (lateEventPolicy == LateEventPolicy.REJECT) {
                // 拒绝计入；eventId 仍登记，保证重复提交判定一致
                return IngestResult.LATE_REJECTED;
            }
            apply(event, windowStart);
            return IngestResult.LATE_ACCUMULATED;
        }

        apply(event, windowStart);
        if (eventMs > maxEventTimeMs) {
            maxEventTimeMs = eventMs;
        }
        return IngestResult.APPLIED;
    }

    private void apply(UsageEvent event, long windowStart) {
        WindowKey key = new WindowKey(event.tenantId(), event.metric(), windowStart);
        MutableWindow w = windows.computeIfAbsent(key, k -> new MutableWindow());
        w.totalQuantity += event.quantity();
        w.eventCount += 1;
    }

    /** 当前水位线（epoch ms）。尚无事件时为 Long.MIN_VALUE。 */
    public long watermarkMs() {
        if (maxEventTimeMs == Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        return maxEventTimeMs - allowedLatenessMs;
    }

    public boolean contains(String eventId) {
        return appliedEventIds.contains(eventId);
    }

    public List<WindowAggregate> aggregates(String tenantId, String metric) {
        long watermark = watermarkMs();
        return windows.entrySet().stream()
                .filter(e -> e.getKey().tenantId().equals(tenantId)
                        && e.getKey().metric().equals(metric))
                .map(e -> toAggregate(e.getKey(), e.getValue(), watermark))
                .sorted(Comparator.comparingLong(WindowAggregate::windowStartEpochMs))
                .toList();
    }

    public Optional<WindowAggregate> aggregateAt(String tenantId, String metric, long windowStartEpochMs) {
        WindowKey key = new WindowKey(tenantId, metric, windowStartEpochMs);
        MutableWindow w = windows.get(key);
        if (w == null) {
            return Optional.empty();
        }
        return Optional.of(toAggregate(key, w, watermarkMs()));
    }

    private WindowAggregate toAggregate(WindowKey key, MutableWindow w, long watermark) {
        long windowEnd = key.windowStart() + windowSizeMs;
        return new WindowAggregate(key.tenantId(), key.metric(), key.windowStart(), windowEnd,
                w.totalQuantity, w.eventCount, windowEnd <= watermark);
    }

    public List<LateEventRecord> lateEvents() {
        return List.copyOf(lateEvents);
    }

    /** 统计与 [fromEpochMs, toEpochMs) 有交集的窗口用量之和（窗口与配额周期对齐时为精确值）。 */
    public long usageBetween(String tenantId, String metric, long fromEpochMs, long toEpochMs) {
        long sum = 0;
        for (Map.Entry<WindowKey, MutableWindow> e : windows.entrySet()) {
            WindowKey k = e.getKey();
            if (!k.tenantId().equals(tenantId) || !k.metric().equals(metric)) {
                continue;
            }
            long windowEnd = k.windowStart() + windowSizeMs;
            if (k.windowStart() < toEpochMs && windowEnd > fromEpochMs) {
                sum += e.getValue().totalQuantity;
            }
        }
        return sum;
    }

    public long windowSizeMs() {
        return windowSizeMs;
    }
}
