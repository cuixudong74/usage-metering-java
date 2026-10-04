package com.example.metering;

import com.example.metering.config.MeteringProperties;
import com.example.metering.core.FileEventJournal;
import com.example.metering.core.MeteringService;
import com.example.metering.core.WindowAggregator;
import com.example.metering.model.QuotaDecision;
import com.example.metering.model.QuotaPolicy;
import com.example.metering.model.UsageEvent;
import com.example.metering.model.WindowAggregate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeteringServiceRecoveryTest {

    private static final long HOUR = 3_600_000L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private MeteringService newService(Path journal) {
        return new MeteringService(new MeteringProperties(), new FileEventJournal(journal));
    }

    @Test
    void restartRebuildsAggregatesAndLateMarkersFromJournal() {
        Path journal = tempDir.resolve("events.jsonl");
        MeteringService first = newService(journal);
        first.report(new UsageEvent("e1", "tenant-r", "api-calls", 1, T0));
        first.report(new UsageEvent("e2", "tenant-r", "api-calls", 2, T0.plusSeconds(10)));
        // 推进水位线关闭窗口 0，随后的事件迟到被拒
        first.report(new UsageEvent("e3", "tenant-r", "api-calls", 4, T0.plusMillis(2 * HOUR)));
        assertEquals(WindowAggregator.IngestResult.LATE_REJECTED,
                first.report(new UsageEvent("late", "tenant-r", "api-calls", 9,
                        T0.plusSeconds(30 * 60))));

        List<WindowAggregate> beforeRestart = first.query("tenant-r", "api-calls");

        // 模拟重启：同一日志文件重建服务
        MeteringService restarted = newService(journal);
        assertEquals(beforeRestart, restarted.query("tenant-r", "api-calls"));
        assertEquals(1, restarted.lateEvents("tenant-r").size());
        assertEquals("late", restarted.lateEvents("tenant-r").get(0).event().eventId());
        // 重启后重复上报仍被去重
        assertEquals(WindowAggregator.IngestResult.DUPLICATE,
                restarted.report(new UsageEvent("e1", "tenant-r", "api-calls", 1, T0)));
    }

    @Test
    void quotaConsumptionIsConsistentAfterRestart() {
        Path journal = tempDir.resolve("events.jsonl");
        Instant now = Instant.now();
        MeteringService first = newService(journal);
        first.upsertPolicy(new QuotaPolicy("tenant-q", "api-calls", Duration.ofDays(1),
                10, 0.5, QuotaPolicy.OverLimitStrategy.BLOCK));
        first.report(new UsageEvent("q1", "tenant-q", "api-calls", 6, now));

        MeteringService restarted = newService(journal);
        restarted.upsertPolicy(new QuotaPolicy("tenant-q", "api-calls", Duration.ofDays(1),
                10, 0.5, QuotaPolicy.OverLimitStrategy.BLOCK));
        QuotaDecision d = restarted.checkQuota("tenant-q", "api-calls", 5);
        assertEquals(6, d.currentUsage());
        assertEquals(4, d.remaining());
        assertTrue(d.alertTriggered()); // 6/10 >= 0.5
        assertEquals(false, d.allowed()); // 6+5 > 10 被拦截
    }
}
