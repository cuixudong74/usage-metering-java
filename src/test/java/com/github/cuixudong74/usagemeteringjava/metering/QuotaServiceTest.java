package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.model.LateEventPolicy;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaConfig;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaDecision;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaRule;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaService;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MeteringStore store;
    private QuotaConfig config;
    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        store = new MeteringStore(Duration.ofHours(1), LateEventPolicy.ACCEPT_AND_MARK);
        config = new QuotaConfig();
        quotaService = new QuotaService(store, config);
    }

    private void report(String id, String tenant, String dimension, long qty) {
        store.ingest(new UsageEvent(id, tenant, dimension, qty, T0.plusSeconds(1)), T0);
    }

    @Test
    void decisionListsMatchedRulesAndRemaining() {
        config.replaceRules(List.of(
                new QuotaRule("r1", "t1", "api_calls", Duration.ofDays(1), 100, 0.8, true),
                new QuotaRule("r2", "*", "api_calls", Duration.ofDays(1), 150, 0.9, false),
                new QuotaRule("r3", "t1", "storage", Duration.ofDays(1), 10, 0.5, true)));
        report("e1", "t1", "api_calls", 30);

        QuotaDecision d = quotaService.evaluate("t1", "api_calls", 20, T0.plusSeconds(2));

        // r3 维度不匹配，不命中；r1、r2 命中
        assertThat(d.matchedRules()).extracting(QuotaDecision.RuleEvaluation::ruleId)
                .containsExactly("r1", "r2");
        QuotaDecision.RuleEvaluation r1 = d.matchedRules().get(0);
        assertThat(r1.used()).isEqualTo(30);
        assertThat(r1.remaining()).isEqualTo(100 - 30 - 20);
        assertThat(r1.exceeded()).isFalse();
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void exceedingBlockingRuleRejects() {
        config.replaceRules(List.of(
                new QuotaRule("r1", "t1", "api_calls", Duration.ofDays(1), 100, 0.8, true)));
        report("e1", "t1", "api_calls", 95);

        QuotaDecision d = quotaService.evaluate("t1", "api_calls", 10, T0.plusSeconds(2));

        assertThat(d.allowed()).isFalse();
        assertThat(d.matchedRules().get(0).exceeded()).isTrue();
        assertThat(d.matchedRules().get(0).remaining()).isZero();
    }

    @Test
    void warnThresholdTriggersWarning() {
        config.replaceRules(List.of(
                new QuotaRule("r1", "t1", "api_calls", Duration.ofDays(1), 100, 0.8, true)));
        report("e1", "t1", "api_calls", 75);

        QuotaDecision d = quotaService.evaluate("t1", "api_calls", 10, T0.plusSeconds(2));

        assertThat(d.allowed()).isTrue();
        assertThat(d.warning()).isTrue();
        assertThat(d.matchedRules().get(0).warnHit()).isTrue();
    }

    @Test
    void thresholdChangeTakesEffectAtRuntimeWithoutRevertingPastDecisions() {
        config.replaceRules(List.of(
                new QuotaRule("r1", "t1", "api_calls", Duration.ofDays(1), 100, 0.9, true)));
        report("e1", "t1", "api_calls", 85);

        // 阈值 0.9：85 < 90，不告警
        QuotaDecision before = quotaService.evaluate("t1", "api_calls", 0, T0.plusSeconds(2));
        assertThat(before.warning()).isFalse();

        // 运行中把阈值调到 0.8
        config.replaceRules(List.of(
                new QuotaRule("r1", "t1", "api_calls", Duration.ofDays(1), 100, 0.8, true)));
        QuotaDecision after = quotaService.evaluate("t1", "api_calls", 0, T0.plusSeconds(3));
        assertThat(after.warning()).isTrue();

        // 已给出的判定是不可变记录，不回退
        assertThat(before.warning()).isFalse();
    }

    @Test
    void periodWindowIsolatesUsageAcrossPeriods() {
        config.replaceRules(List.of(
                new QuotaRule("r1", "t1", "api_calls", Duration.ofHours(1), 10, 0.8, true)));
        // 用量在第一个小时窗口
        report("e1", "t1", "api_calls", 9);

        // 在下一个周期判定：上一周期的用量不计入
        QuotaDecision d = quotaService.evaluate("t1", "api_calls", 5, T0.plus(Duration.ofHours(2)));
        assertThat(d.matchedRules().get(0).used()).isZero();
        assertThat(d.allowed()).isTrue();
    }
}
