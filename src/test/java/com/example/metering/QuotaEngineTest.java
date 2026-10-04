package com.example.metering;

import com.example.metering.core.QuotaEngine;
import com.example.metering.model.QuotaDecision;
import com.example.metering.model.QuotaPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaEngineTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:30:00Z");

    /** 用可变的用量表模拟聚合器。 */
    private static final class FakeUsage implements QuotaEngine.UsageLookup {
        final Map<String, Long> usage = new ConcurrentHashMap<>();

        void set(String tenantId, String metric, long value) {
            usage.put(tenantId + "|" + metric, value);
        }

        @Override
        public long usage(String tenantId, String metric, long fromMs, long toMs) {
            return usage.getOrDefault(tenantId + "|" + metric, 0L);
        }
    }

    private QuotaEngine engineWith(FakeUsage usage) {
        QuotaEngine engine = new QuotaEngine();
        engine.setUsageLookup(usage);
        return engine;
    }

    @Test
    void decisionExplainsMatchedQuotaAndRemaining() {
        FakeUsage usage = new FakeUsage();
        usage.set("t1", "api-calls", 70);
        QuotaEngine engine = engineWith(usage);
        engine.upsertPolicy(new QuotaPolicy("t1", "api-calls", Duration.ofHours(1),
                100, 0.8, QuotaPolicy.OverLimitStrategy.ALERT_ONLY));

        QuotaDecision d = engine.evaluate("t1", "api-calls", 10, NOW);
        assertTrue(d.allowed());
        assertFalse(d.alertTriggered());
        assertEquals(70, d.currentUsage());
        assertEquals(100, d.limit());
        assertEquals(30, d.remaining());
        assertTrue(d.matchedQuotaKey().contains("t1"));
        assertTrue(d.reason().contains("usage=70"));
    }

    @Test
    void alertTriggersAtThresholdAndBlockRejectsOverLimit() {
        FakeUsage usage = new FakeUsage();
        usage.set("t1", "api-calls", 85);
        QuotaEngine engine = engineWith(usage);
        engine.upsertPolicy(new QuotaPolicy("t1", "api-calls", Duration.ofHours(1),
                100, 0.8, QuotaPolicy.OverLimitStrategy.BLOCK));

        QuotaDecision alertOnly = engine.evaluate("t1", "api-calls", 10, NOW);
        assertTrue(alertOnly.allowed());       // 85+10 <= 100
        assertTrue(alertOnly.alertTriggered()); // 85/100 >= 0.8

        QuotaDecision blocked = engine.evaluate("t1", "api-calls", 20, NOW);
        assertFalse(blocked.allowed());         // 85+20 > 100
        assertTrue(blocked.reason().contains("blocked=true"));
    }

    @Test
    void multiplePeriodsCombineAndMostRestrictiveWins() {
        FakeUsage usage = new FakeUsage();
        usage.set("t1", "api-calls", 95);
        QuotaEngine engine = engineWith(usage);
        engine.upsertPolicy(new QuotaPolicy("t1", "api-calls", Duration.ofHours(1),
                100, 0.9, QuotaPolicy.OverLimitStrategy.BLOCK));
        engine.upsertPolicy(new QuotaPolicy("t1", "api-calls", Duration.ofDays(1),
                10_000, 0.9, QuotaPolicy.OverLimitStrategy.ALERT_ONLY));

        QuotaDecision d = engine.evaluate("t1", "api-calls", 10, NOW);
        assertFalse(d.allowed()); // 小时配额超限拦截
        assertEquals(2, engine.policies().size());
        assertTrue(d.reason().contains("quota["));
    }

    @Test
    void noPolicyMatchedAllowsByDefault() {
        QuotaEngine engine = engineWith(new FakeUsage());
        QuotaDecision d = engine.evaluate("ghost", "nothing", 1, NOW);
        assertTrue(d.allowed());
        assertTrue(d.reason().contains("no quota policy matched"));
    }

    @Test
    void thresholdChangeTakesEffectWithoutRevertingPastDecisions() {
        FakeUsage usage = new FakeUsage();
        usage.set("t1", "api-calls", 60);
        QuotaEngine engine = engineWith(usage);
        engine.upsertPolicy(new QuotaPolicy("t1", "api-calls", Duration.ofHours(1),
                100, 0.5, QuotaPolicy.OverLimitStrategy.ALERT_ONLY));

        QuotaDecision before = engine.evaluate("t1", "api-calls", 0, NOW);
        assertTrue(before.alertTriggered()); // 60/100 >= 0.5

        // 运行中调高阈值：后续判定不再告警
        engine.upsertPolicy(new QuotaPolicy("t1", "api-calls", Duration.ofHours(1),
                100, 0.9, QuotaPolicy.OverLimitStrategy.ALERT_ONLY));
        QuotaDecision after = engine.evaluate("t1", "api-calls", 0, NOW);
        assertFalse(after.alertTriggered());

        // 历史判定不回退
        var log = engine.decisionLog();
        assertEquals(2, log.size());
        assertTrue(log.get(0).alertTriggered());
        assertFalse(log.get(1).alertTriggered());
    }
}
