package com.github.cuixudong74.usagemeteringjava.metering.store;

import com.github.cuixudong74.usagemeteringjava.metering.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 计量存储：负责事件去重、窗口聚合与迟到处理。
 *
 * 并发模型：按租户分段读写锁。同一租户的写入互斥（避免丢失更新），
 * 查询持读锁拿到一致性快照（不会读到中间状态）；不同租户之间可并行。
 *
 * 乱序安全：窗口归属只由事件自身时间决定，与到达顺序无关；
 * 幂等：eventId 去重，重复事件返回 DUPLICATE 且不重复计入。
 */
public class MeteringStore {

    /** 单窗口可变状态，所有访问须持有对应租户的锁。 */
    static final class WindowState {
        long total;
        long eventCount;
        long lateEventCount;
        boolean closed;

        WindowAggregate snapshot(WindowKey key) {
            return new WindowAggregate(key, total, eventCount, lateEventCount, closed);
        }
    }

    /** 事件处理痕迹，供迟到/重复事件追溯。 */
    public record TraceRecord(String eventId, IngestStatus status, Instant windowStart,
                              Instant processedAt) {
    }

    private final Duration windowSize;
    private final LateEventPolicy latePolicy;
    private volatile Duration gracePeriod;

    private final Map<WindowKey, WindowState> windows = new ConcurrentHashMap<>();
    private final Map<String, TraceRecord> traces = new ConcurrentHashMap<>();
    private final Map<String, ReentrantReadWriteLock> tenantLocks = new ConcurrentHashMap<>();

    public MeteringStore(Duration windowSize, LateEventPolicy latePolicy) {
        this.windowSize = windowSize;
        this.latePolicy = latePolicy;
        this.gracePeriod = windowSize;
    }

    public Duration windowSize() {
        return windowSize;
    }

    public Duration gracePeriod() {
        return gracePeriod;
    }

    /** 窗口宽限期：窗口结束后超过该时长即视为关闭。运行中可调。 */
    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    /** 将事件时间对齐到窗口起点。 */
    public Instant windowStartOf(Instant t) {
        long sizeMillis = windowSize.toMillis();
        long epoch = t.toEpochMilli();
        return Instant.ofEpochMilli(Math.floorDiv(epoch, sizeMillis) * sizeMillis);
    }

    private ReentrantReadWriteLock lockFor(String tenantId) {
        return tenantLocks.computeIfAbsent(tenantId, k -> new ReentrantReadWriteLock());
    }

    /** 摄入事件，幂等且乱序安全。 */
    public IngestResult ingest(UsageEvent event, Instant now) {
        if (event.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + event.eventId());
        }
        ReentrantReadWriteLock lock = lockFor(event.tenantId());
        lock.writeLock().lock();
        try {
            TraceRecord existing = traces.get(event.eventId());
            if (existing != null) {
                return new IngestResult(IngestStatus.DUPLICATE, existing.windowStart(),
                        traceIdOf(event.eventId()));
            }
            Instant windowStart = windowStartOf(event.eventTime());
            WindowKey key = new WindowKey(event.tenantId(), event.dimension(), windowStart);
            WindowState state = windows.computeIfAbsent(key, k -> new WindowState());

            boolean windowClosed = state.closed
                    || !windowStart.plus(windowSize).plus(gracePeriod).isAfter(now);
            IngestStatus status;
            if (windowClosed) {
                if (latePolicy == LateEventPolicy.ACCEPT_AND_MARK) {
                    state.total += event.quantity();
                    state.eventCount++;
                    state.lateEventCount++;
                    status = IngestStatus.LATE_ACCEPTED;
                } else {
                    status = IngestStatus.LATE_REJECTED;
                }
            } else {
                state.total += event.quantity();
                state.eventCount++;
                status = IngestStatus.ACCEPTED;
            }
            traces.put(event.eventId(),
                    new TraceRecord(event.eventId(), status, windowStart, now));
            return new IngestResult(status, windowStart, traceIdOf(event.eventId()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 查询某租户某维度与 [from, to) 有重叠的各窗口聚合快照，按窗口起点升序。 */
    public List<WindowAggregate> query(String tenantId, String dimension, Instant from, Instant to) {
        ReentrantReadWriteLock lock = lockFor(tenantId);
        lock.readLock().lock();
        try {
            List<WindowAggregate> result = new ArrayList<>();
            for (Map.Entry<WindowKey, WindowState> e : windows.entrySet()) {
                WindowKey k = e.getKey();
                boolean overlaps = k.windowStart().isBefore(to)
                        && k.windowStart().plus(windowSize).isAfter(from);
                if (k.tenantId().equals(tenantId) && k.dimension().equals(dimension) && overlaps) {
                    result.add(e.getValue().snapshot(k));
                }
            }
            result.sort(Comparator.comparing(w -> w.key().windowStart()));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 汇总 [from, to) 内的用量（用于配额判定），读一致性快照。 */
    public long sumUsage(String tenantId, String dimension, Instant from, Instant to) {
        return query(tenantId, dimension, from, to).stream()
                .mapToLong(WindowAggregate::total).sum();
    }

    /** 关闭窗口终点早于 cutoff 的窗口，返回新关闭的窗口数。 */
    public int closeWindowsBefore(Instant cutoff) {
        int closed = 0;
        for (Map.Entry<WindowKey, WindowState> e : windows.entrySet()) {
            WindowKey k = e.getKey();
            if (k.windowStart().plus(windowSize).isBefore(cutoff)
                    || k.windowStart().plus(windowSize).equals(cutoff)) {
                ReentrantReadWriteLock lock = lockFor(k.tenantId());
                lock.writeLock().lock();
                try {
                    if (!e.getValue().closed) {
                        e.getValue().closed = true;
                        closed++;
                    }
                } finally {
                    lock.writeLock().unlock();
                }
            }
        }
        return closed;
    }

    /** 查询某事件的处理痕迹（迟到/重复可追溯）。 */
    public Optional<String> traceOf(String eventId) {
        TraceRecord rec = traces.get(eventId);
        return Optional.ofNullable(rec).map(r ->
                "event=%s status=%s window=%s processedAt=%s".formatted(
                        r.eventId(), r.status(), r.windowStart(), r.processedAt()));
    }

    /** 一致性快照：所有窗口聚合（用于落盘与对账）。 */
    public List<WindowAggregate> snapshotAggregates() {
        List<WindowAggregate> out = new ArrayList<>();
        for (Map.Entry<WindowKey, WindowState> e : windows.entrySet()) {
            ReentrantReadWriteLock lock = lockFor(e.getKey().tenantId());
            lock.readLock().lock();
            try {
                out.add(e.getValue().snapshot(e.getKey()));
            } finally {
                lock.readLock().unlock();
            }
        }
        out.sort(Comparator.comparing((WindowAggregate a) -> a.key().tenantId())
                .thenComparing(a -> a.key().dimension())
                .thenComparing(a -> a.key().windowStart()));
        return out;
    }

    /** 一致性快照：全部事件处理痕迹（去重依据，随窗口一起落盘）。 */
    public List<TraceRecord> snapshotTraces() {
        return traces.values().stream()
                .sorted(Comparator.comparing(TraceRecord::eventId))
                .toList();
    }

    /** 恢复时载入窗口聚合（仅在启动阶段、无并发写入时调用）。 */
    public void loadAggregates(List<WindowAggregate> aggregates) {
        for (WindowAggregate a : aggregates) {
            WindowState s = new WindowState();
            s.total = a.total();
            s.eventCount = a.eventCount();
            s.lateEventCount = a.lateEventCount();
            s.closed = a.closed();
            windows.put(a.key(), s);
        }
    }

    /** 恢复时载入事件痕迹（仅在启动阶段调用）。 */
    public void loadTraces(List<TraceRecord> records) {
        for (TraceRecord r : records) {
            traces.put(r.eventId(), r);
        }
    }

    private static String traceIdOf(String eventId) {
        return "trace-" + eventId;
    }
}
