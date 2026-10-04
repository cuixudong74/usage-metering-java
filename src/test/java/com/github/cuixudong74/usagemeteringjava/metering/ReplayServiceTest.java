package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.model.LateEventPolicy;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayReport;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayService;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MeteringStore newStore() {
        return new MeteringStore(Duration.ofHours(1), LateEventPolicy.ACCEPT_AND_MARK);
    }

    private static UsageEvent event(String id, long qty, Instant time) {
        return new UsageEvent(id, "t1", "api_calls", qty, time);
    }

    @Test
    void replayWithConsistentHistoryReportsNoDiff() {
        MeteringStore store = newStore();
        List<UsageEvent> batch = List.of(
                event("e1", 10, T0.plusSeconds(1)),
                event("e2", 20, T0.plusSeconds(3700)));
        batch.forEach(e -> store.ingest(e, T0.plus(Duration.ofHours(3))));

        ReplayReport report = new ReplayService(store).replay(batch);

        assertThat(report.totalEvents()).isEqualTo(2);
        assertThat(report.diffs()).isEmpty();
        assertThat(report.diffByCategory().get(ReplayReport.DiffCategory.NONE)).isEqualTo(2);
    }

    @Test
    void missingEventInLiveStoreIsClassified() {
        MeteringStore store = newStore();
        // 线上只计入了 e1（e2 当时迟到被拒或重启丢失）
        store.ingest(event("e1", 10, T0.plusSeconds(1)), T0);

        ReplayReport report = new ReplayService(store).replay(List.of(
                event("e1", 10, T0.plusSeconds(1)),
                event("e2", 20, T0.plusSeconds(2))));

        assertThat(report.diffs()).hasSize(1);
        ReplayReport.WindowDiff diff = report.diffs().get(0);
        assertThat(diff.category()).isEqualTo(ReplayReport.DiffCategory.MISSING_EVENT);
        assertThat(diff.beforeTotal()).isEqualTo(10);
        assertThat(diff.afterTotal()).isEqualTo(30);
        assertThat(report.diffByCategory().get(ReplayReport.DiffCategory.MISSING_EVENT)).isEqualTo(1);
    }

    @Test
    void overCountedWindowIsClassifiedAsValueMismatch() {
        MeteringStore store = newStore();
        // 线上计入了一个不在权威批次里的事件（如脏数据/重复计入）
        store.ingest(event("e1", 10, T0.plusSeconds(1)), T0);
        store.ingest(event("ghost", 7, T0.plusSeconds(2)), T0);

        ReplayReport report = new ReplayService(store).replay(List.of(
                event("e1", 10, T0.plusSeconds(1))));

        assertThat(report.diffs()).hasSize(1);
        ReplayReport.WindowDiff diff = report.diffs().get(0);
        assertThat(diff.category()).isEqualTo(ReplayReport.DiffCategory.VALUE_MISMATCH);
        assertThat(diff.beforeTotal()).isEqualTo(17);
        assertThat(diff.afterTotal()).isEqualTo(10);
    }

    @Test
    void replayIsReadOnlyAndOrderIndependent() {
        MeteringStore store = newStore();
        List<UsageEvent> batch = List.of(
                event("e1", 10, T0.plusSeconds(1)),
                event("e2", 20, T0.plusSeconds(3700)));
        batch.forEach(e -> store.ingest(e, T0.plus(Duration.ofHours(3))));
        var before = store.snapshotAggregates();

        ReplayService replay = new ReplayService(store);
        // 乱序批次与正序批次结论一致
        ReplayReport r1 = replay.replay(batch);
        ReplayReport r2 = replay.replay(List.of(batch.get(1), batch.get(0)));
        assertThat(r2.diffs()).isEqualTo(r1.diffs());
        // 回放不修改线上存储
        assertThat(store.snapshotAggregates()).isEqualTo(before);
    }

    @Test
    void replayScopeIsLimitedToBatchTenantsAndDimensions() {
        MeteringStore store = newStore();
        store.ingest(event("e1", 10, T0.plusSeconds(1)), T0);
        // 另一个租户的窗口不在批次范围内，不参与对比
        store.ingest(new UsageEvent("other", "t2", "api_calls", 99, T0.plusSeconds(1)), T0);

        ReplayReport report = new ReplayService(store).replay(List.of(
                event("e1", 10, T0.plusSeconds(1))));

        assertThat(report.diffs()).isEmpty();
    }
}
