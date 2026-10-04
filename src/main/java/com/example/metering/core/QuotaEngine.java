package com.example.metering.core;

import com.example.metering.model.QuotaDecision;
import com.example.metering.model.QuotaPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配额引擎：按 (tenantId, metric, period) 组合配置配额，判定结果可解释。
 *
 * <p>语义：
 * <ul>
 *   <li>同一 (tenantId, metric) 可配置多个不同 period 的配额，全部参与判定，
 *       任一 BLOCK 策略超限则拒绝。</li>
 *   <li>阈值与限额通过 upsertPolicy 运行中生效，仅影响之后的判定；
 *       已写入 decisionLog 的历史判定永不修改（不回退）。</li>
 *   <li>usageLookup 由外层在持有聚合器锁的情况下提供，保证判定读到一致快照。</li>
 * </ul>
 */
public class QuotaEngine {

    /** 查询 [fromMs, toMs) 范围内用量的回调。 */
    @FunctionalInterface
    public interface UsageLookup {
        long usage(String tenantId, String metric, long fromMs, long toMs);
    }

    private static final int DECISION_LOG_CAPACITY = 10_000;

    private final Map<String, QuotaPolicy> policies = new LinkedHashMap<>();
    private final List<QuotaDecision> decisionLog = new CopyOnWriteArrayList<>();
    private volatile UsageLookup usageLookup = (t, m, f, to) -> 0L;

    public void setUsageLookup(UsageLookup usageLookup) {
        this.usageLookup = usageLookup;
    }

    public synchronized void upsertPolicy(QuotaPolicy policy) {
        policies.put(quotaKey(policy.tenantId(), policy.metric(), policy.period().toMillis()), policy);
    }

    public synchronized List<QuotaPolicy> policies() {
        return List.copyOf(policies.values());
    }

    public static String quotaKey(String tenantId, String metric, long periodMs) {
        return tenantId + "|" + metric + "|" + periodMs;
    }

    /**
     * 判定 tenantId/metric 追加 requested 用量是否放行。
     * 无匹配配额时默认放行并说明原因。
     */
    public QuotaDecision evaluate(String tenantId, String metric, long requested, Instant now) {
        List<QuotaPolicy> matched;
        synchronized (this) {
            matched = policies.values().stream()
                    .filter(p -> p.tenantId().equals(tenantId) && p.metric().equals(metric))
                    .toList();
        }
        Instant decidedAt = Instant.now();
        if (matched.isEmpty()) {
            return log(new QuotaDecision(tenantId, metric, true, false, 0, -1, -1,
                    null, "no quota policy matched, allowed by default", decidedAt));
        }

        boolean allowed = true;
        boolean alert = false;
        long worstRemaining = Long.MAX_VALUE;
        long representativeUsage = 0;
        long representativeLimit = -1;
        String decisiveKey = null;
        StringBuilder reason = new StringBuilder();

        for (QuotaPolicy p : matched) {
            long periodMs = p.period().toMillis();
            long nowMs = now.toEpochMilli();
            long periodStart = Math.floorDiv(nowMs, periodMs) * periodMs;
            long usage = usageLookup.usage(tenantId, metric, periodStart, periodStart + periodMs);
            long remaining = p.limit() - usage;
            boolean quotaAlert = p.limit() > 0
                    && (double) usage / p.limit() >= p.alertThresholdRatio();
            boolean quotaBlocked = p.overLimitStrategy() == QuotaPolicy.OverLimitStrategy.BLOCK
                    && usage + requested > p.limit();

            String key = quotaKey(p.tenantId(), p.metric(), periodMs);
            reason.append(String.format(
                    "quota[%s] usage=%d limit=%d remaining=%d alert=%s blocked=%s; ",
                    key, usage, p.limit(), remaining, quotaAlert, quotaBlocked));

            alert |= quotaAlert;
            if (quotaBlocked) {
                allowed = false;
                decisiveKey = key;
            }
            if (remaining < worstRemaining) {
                worstRemaining = remaining;
                representativeUsage = usage;
                representativeLimit = p.limit();
                if (decisiveKey == null) {
                    decisiveKey = key;
                }
            }
        }

        return log(new QuotaDecision(tenantId, metric, allowed, alert,
                representativeUsage, representativeLimit,
                worstRemaining == Long.MAX_VALUE ? -1 : worstRemaining,
                decisiveKey, reason.toString().trim(), decidedAt));
    }

    private QuotaDecision log(QuotaDecision decision) {
        decisionLog.add(decision);
        while (decisionLog.size() > DECISION_LOG_CAPACITY) {
            decisionLog.remove(0);
        }
        return decision;
    }

    /** 历史判定日志，只增不改，保证已给出的判定不回退。 */
    public List<QuotaDecision> decisionLog() {
        return List.copyOf(decisionLog);
    }
}
