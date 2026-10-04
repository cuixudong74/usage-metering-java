package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.model.IngestStatus;
import com.github.cuixudong74.usagemeteringjava.metering.model.LateEventPolicy;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.persist.SnapshotStore;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaConfig;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaDecision;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaRule;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaService;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayReport;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayService;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MeteringStore newStore() {
        return new MeteringStore(Duration.ofHours(1), LateEventPolicy.ACCEPT_AND_MARK);
    }

    @Test
    void restartRestoresAggregatesAndDeduplication(@TempDir Path dir) {
        MeteringStore before = newStore();
        before.ingest(new UsageEvent("e1", "t1", "api_calls", 10, T0.plusSeconds(1)), T0);
        before.ingest(new UsageEvent("e2", "t1", "api_calls", 20, T0.plusSeconds(3700)), T0);
        before.ingest(new UsageEvent("e3", "t2", "api_calls", 5, T0.plusSeconds(2)), T0);

        SnapshotStore snapshots = new SnapshotStore(dir);
        snapshots.save(before);

        // 模拟重启：全新空存储 + 恢复
        MeteringStore after = newStore();
        assertThat(snapshots.restore(after)).isTrue();

        // 计量结果一致
        assertThat(after.snapshotAggregates()).isEqualTo(before.snapshotAggregates());
        // 去重状态恢复：重复提交同一事件不会被重复计入
        assertThat(after.ingest(new UsageEvent("e1", "t1", "api_calls", 10, T0.plusSeconds(1)), T0)
                .status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(after.sumUsage("t1", "api_calls", T0, T0.plus(Duration.ofHours(1)))).isEqualTo(10);
        // 处理痕迹可追溯
        assertThat(after.traceOf("e1")).isPresent();
    }

    @Test
    void quotaConsumptionIsConsistentAfterRestart(@TempDir Path dir) {
        MeteringStore before = newStore();
        before.ingest(new UsageEvent("e1", "t1", "api_calls", 80, T0.plusSeconds(1)), T0);
        SnapshotStore snapshots = new SnapshotStore(dir);
        snapshots.save(before);

        MeteringStore after = newStore();
        snapshots.restore(after);

        QuotaConfig config = new QuotaConfig();
        config.replaceRules(List.of(
                new QuotaRule("r1", "t1", "api_calls", Duration.ofDays(1), 100, 0.8, true)));
        QuotaService quota = new QuotaService(after, config);

        // 恢复后配额消耗自洽：已用 80，再申请 30 超限被拦截
        QuotaDecision d = quota.evaluate("t1", "api_calls", 30, T0.plusSeconds(2));
        assertThat(d.matchedRules().get(0).used()).isEqualTo(80);
        assertThat(d.allowed()).isFalse();
    }

    @Test
    void unpersistedStateIsDiscardedAndCanBeRebuiltByReplay(@TempDir Path dir) {
        MeteringStore store = newStore();
        store.ingest(new UsageEvent("e1", "t1", "api_calls", 10, T0.plusSeconds(1)), T0);
        SnapshotStore snapshots = new SnapshotStore(dir);
        snapshots.save(store);
        // 快照之后、宕机之前的在途事件
        store.ingest(new UsageEvent("e2", "t1", "api_calls", 20, T0.plusSeconds(2)), T0);

        MeteringStore restored = newStore();
        snapshots.restore(restored);
        // 未落盘的 e2 被丢弃
        assertThat(restored.sumUsage("t1", "api_calls", T0, T0.plus(Duration.ofHours(1))))
                .isEqualTo(10);

        // 通过回放重建：重放含 e2 的历史批次后差异归零
        ReplayService replay = new ReplayService(restored);
        var report = replay.replay(List.of(
                new UsageEvent("e1", "t1", "api_calls", 10, T0.plusSeconds(1)),
                new UsageEvent("e2", "t1", "api_calls", 20, T0.plusSeconds(2))));
        assertThat(report.diffs()).hasSize(1);
        assertThat(report.diffs().get(0).category())
                .isEqualTo(ReplayReport.DiffCategory.MISSING_EVENT);
        assertThat(report.diffs().get(0).beforeTotal()).isEqualTo(10);
        assertThat(report.diffs().get(0).afterTotal()).isEqualTo(30);
    }

    @Test
    void snapshotWithMismatchedWindowSizeIsDiscarded(@TempDir Path dir) {
        MeteringStore before = newStore();
        before.ingest(new UsageEvent("e1", "t1", "api_calls", 10, T0.plusSeconds(1)), T0);
        SnapshotStore snapshots = new SnapshotStore(dir);
        snapshots.save(before);

        // 窗口大小配置变了，旧快照不可解释，应丢弃
        MeteringStore other = new MeteringStore(Duration.ofMinutes(30),
                LateEventPolicy.ACCEPT_AND_MARK);
        assertThat(snapshots.restore(other)).isFalse();
        assertThat(other.snapshotAggregates()).isEmpty();
    }
}
