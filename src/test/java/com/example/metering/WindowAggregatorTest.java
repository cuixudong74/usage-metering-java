package com.example.metering;

import com.example.metering.core.LateEventPolicy;
import com.example.metering.core.WindowAggregator;
import com.example.metering.model.UsageEvent;
import com.example.metering.model.WindowAggregate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowAggregatorTest {

    private static final long HOUR = 3_600_000L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long BASE = T0.toEpochMilli();

    private WindowAggregator newAggregator(LateEventPolicy policy) {
        return new WindowAggregator(HOUR, 600_000L, policy);
    }

    private UsageEvent event(String id, long offsetMs, long quantity) {
        return new UsageEvent(id, "tenant-1", "api-calls", quantity, T0.plusMillis(offsetMs));
    }

    @Test
    void duplicateEventIsCountedOnce() {
        WindowAggregator agg = newAggregator(LateEventPolicy.REJECT);
        assertEquals(WindowAggregator.IngestResult.APPLIED, agg.ingest(event("e1", 1000, 5)));
        assertEquals(WindowAggregator.IngestResult.DUPLICATE, agg.ingest(event("e1", 1000, 5)));

        WindowAggregate w = agg.aggregates("tenant-1", "api-calls").get(0);
        assertEquals(5, w.totalQuantity());
        assertEquals(1, w.eventCount());
    }

    @Test
    void outOfOrderEventsProduceSameResultAsOrdered() {
        WindowAggregator ordered = newAggregator(LateEventPolicy.REJECT);
        WindowAggregator shuffled = newAggregator(LateEventPolicy.REJECT);
        List<UsageEvent> events = List.of(
                event("a", 60_000, 1),
                event("b", 3_000_000, 2),
                event("c", 1_200_000, 3),
                event("d", 2_400_000, 4));
        events.forEach(ordered::ingest);
        // 乱序到达
        shuffled.ingest(events.get(2));
        shuffled.ingest(events.get(0));
        shuffled.ingest(events.get(3));
        shuffled.ingest(events.get(1));

        assertEquals(ordered.aggregates("tenant-1", "api-calls"),
                shuffled.aggregates("tenant-1", "api-calls"));
    }

    @Test
    void eventsAreAssignedToWindowsByEventTime() {
        WindowAggregator agg = newAggregator(LateEventPolicy.REJECT);
        agg.ingest(event("e1", HOUR - 60_000, 1));   // 00:59 -> window 0
        agg.ingest(event("e2", HOUR + 60_000, 2));   // 01:01 -> window 1

        List<WindowAggregate> windows = agg.aggregates("tenant-1", "api-calls");
        assertEquals(2, windows.size());
        assertEquals(BASE, windows.get(0).windowStartEpochMs());
        assertEquals(1, windows.get(0).totalQuantity());
        assertEquals(BASE + HOUR, windows.get(1).windowStartEpochMs());
        assertEquals(2, windows.get(1).totalQuantity());
    }

    @Test
    void lateEventAfterWindowClosedIsRejectedAndMarked() {
        WindowAggregator agg = newAggregator(LateEventPolicy.REJECT);
        // 推进水位线：02:00 到达 -> watermark = 01:50，窗口 [00:00,01:00) 关闭
        agg.ingest(event("e1", 2 * HOUR, 1));
        assertEquals(WindowAggregator.IngestResult.LATE_REJECTED,
                agg.ingest(event("late", 30 * 60_000, 9)));

        assertEquals(1, agg.lateEvents().size());
        assertEquals("late", agg.lateEvents().get(0).event().eventId());
        assertEquals("REJECT", agg.lateEvents().get(0).policyApplied());
        // 未计入已关闭窗口
        assertTrue(agg.aggregateAt("tenant-1", "api-calls", BASE).isEmpty());
        // 重复提交同一迟到事件，判定保持一致
        assertEquals(WindowAggregator.IngestResult.DUPLICATE,
                agg.ingest(event("late", 30 * 60_000, 9)));
    }

    @Test
    void lateEventAfterWindowClosedIsAccumulatedWhenPolicyAllows() {
        WindowAggregator agg = newAggregator(LateEventPolicy.ACCUMULATE);
        agg.ingest(event("e1", 2 * HOUR, 1));
        assertEquals(WindowAggregator.IngestResult.LATE_ACCUMULATED,
                agg.ingest(event("late", 30 * 60_000, 9)));

        assertEquals(1, agg.lateEvents().size());
        WindowAggregate closedWindow = agg.aggregateAt("tenant-1", "api-calls", BASE).orElseThrow();
        assertEquals(9, closedWindow.totalQuantity());
        assertTrue(closedWindow.closed());
    }

    @Test
    void usageBetweenSumsOverlappingWindows() {
        WindowAggregator agg = newAggregator(LateEventPolicy.REJECT);
        agg.ingest(event("e1", 1000, 1));            // window 0
        agg.ingest(event("e2", HOUR + 1000, 2));     // window 1
        agg.ingest(event("e3", 2 * HOUR + 1000, 4)); // window 2

        assertEquals(3, agg.usageBetween("tenant-1", "api-calls", BASE, BASE + 2 * HOUR));
        assertEquals(7, agg.usageBetween("tenant-1", "api-calls", BASE, BASE + 3 * HOUR));
        assertEquals(0, agg.usageBetween("tenant-1", "other", BASE, BASE + 3 * HOUR));
    }
}
