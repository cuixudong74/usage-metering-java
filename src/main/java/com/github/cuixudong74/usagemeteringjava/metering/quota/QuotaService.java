package com.github.cuixudong74.usagemeteringjava.metering.quota;

import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 配额判定服务：基于计量结果给出可解释判定。
 * 判定结果包含每条命中规则的限额、已用、剩余与阈值命中情况。
 */
public class QuotaService {

    private final MeteringStore store;
    private final QuotaConfig config;

    public QuotaService(MeteringStore store, QuotaConfig config) {
        this.store = store;
        this.config = config;
    }

    /**
     * 对租户某维度的一次拟用量做配额判定。
     *
     * @param quantity 本次拟消耗的用量（会计入投影，不实际扣减）
     */
    public QuotaDecision evaluate(String tenantId, String dimension, long quantity, Instant now) {
        List<QuotaRule> rules = config.rules(); // 原子快照，判定期间不受热更新影响
        List<QuotaDecision.RuleEvaluation> evaluations = new ArrayList<>();
        boolean allowed = true;
        boolean warning = false;
        for (QuotaRule rule : rules) {
            if (!matches(rule.tenantId(), tenantId) || !matches(rule.dimension(), dimension)) {
                continue;
            }
            Instant periodStart = periodStartOf(now, rule.period().toMillis());
            Instant periodEnd = periodStart.plus(rule.period());
            long used = store.sumUsage(tenantId, dimension, periodStart, periodEnd);
            long projected = used + quantity;
            boolean exceeded = projected > rule.limit();
            boolean warnHit = projected >= rule.limit() * rule.warnThreshold();
            long remaining = Math.max(0, rule.limit() - projected);
            evaluations.add(new QuotaDecision.RuleEvaluation(
                    rule.ruleId(), rule.limit(), used, remaining, exceeded, warnHit));
            if (exceeded && rule.blockOnExceed()) {
                allowed = false;
            }
            if (warnHit) {
                warning = true;
            }
        }
        return new QuotaDecision(allowed, warning, List.copyOf(evaluations));
    }

    private static boolean matches(String pattern, String value) {
        return "*".equals(pattern) || pattern.equals(value);
    }

    private static Instant periodStartOf(Instant t, long periodMillis) {
        long epoch = t.toEpochMilli();
        return Instant.ofEpochMilli(Math.floorDiv(epoch, periodMillis) * periodMillis);
    }
}
