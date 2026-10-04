package com.example.metering;

import com.example.metering.config.MeteringProperties;
import com.example.metering.core.InMemoryEventJournal;
import com.example.metering.core.MeteringService;
import com.example.metering.model.ReconcileReport;
import com.example.metering.model.UsageEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeteringServiceReconcileTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MeteringService service =
            new MeteringService(new MeteringProperties(), new InMemoryEventJournal());

    private UsageEvent event(String id, long offsetMs, long quantity) {
        return new UsageEvent(id, "tenant-a", "api-calls", quantity, T0.plusMillis(offsetMs));
    }

    private List<ReconcileReport.DiffType> diffTypes(ReconcileReport report) {
        return report.diffs().stream().map(ReconcileReport.DiffEntry::type).toList();
    }

    @Test
    void identicalBatchProducesNoDiffs() {
        List<UsageEvent> batch = List.of(event("a1", 0, 1), event("a2", 10_000, 2));
        batch.forEach(service::report);

        ReconcileReport report = service.reconcile(batch);
        assertEquals(0, report.diffCount(), () -> "unexpected diffs: " + report.diffs());
    }

    @Test
    void duplicateWithinBatchIsClassified() {
        UsageEvent a1 = event("a1", 0, 1);
        service.report(a1);

        ReconcileReport report = service.reconcile(List.of(a1, a1));
        assertTrue(diffTypes(report).contains(ReconcileReport.DiffType.DUPLICATE_IGNORED));
    }

    @Test
    void missingExtraAndMismatchAreClassified() {
        service.report(event("a1", 0, 1));
        service.report(event("a2", 10_000, 2));
        service.report(event("b1", 3_600_000, 4)); // 窗口 1

        // 批次多出窗口 2 的事件 -> MISSING_IN_CURRENT
        ReconcileReport missing = service.reconcile(List.of(
                event("a1", 0, 1), event("a2", 10_000, 2),
                event("b1", 3_600_000, 4), event("c1", 7_200_000, 9)));
        assertTrue(diffTypes(missing).contains(ReconcileReport.DiffType.MISSING_IN_CURRENT));

        // 批次缺少窗口 1 的事件 -> EXTRA_IN_CURRENT
        ReconcileReport extra = service.reconcile(List.of(
                event("a1", 0, 1), event("a2", 10_000, 2)));
        assertTrue(diffTypes(extra).contains(ReconcileReport.DiffType.EXTRA_IN_CURRENT));

        // 批次中数量被篡改 -> QUANTITY_MISMATCH
        ReconcileReport mismatch = service.reconcile(List.of(
                event("a1", 0, 100), event("a2", 10_000, 2), event("b1", 3_600_000, 4)));
        assertTrue(diffTypes(mismatch).contains(ReconcileReport.DiffType.QUANTITY_MISMATCH));
    }

    @Test
    void lateEventInBatchIsClassified() {
        // 重放时：3 小时处的事件推进水位线，30 分钟处的事件落入已关闭窗口
        ReconcileReport report = service.reconcile(List.of(
                event("future", 3 * 3_600_000L, 1),
                event("late", 30 * 60_000L, 1)));
        assertTrue(diffTypes(report).contains(ReconcileReport.DiffType.LATE_TO_CLOSED_WINDOW));
    }
}
