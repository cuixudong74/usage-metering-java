package com.github.cuixudong74.usagemeteringjava.metering.quota;

import java.util.List;

/**
 * 配额判定结果。
 *
 * @param allowed      是否放行
 * @param warning      是否触发告警阈值
 * @param matchedRules 命中的规则及剩余额度明细
 */
public record QuotaDecision(boolean allowed, boolean warning,
                            List<RuleEvaluation> matchedRules) {

    /** 单条规则的评估明细。 */
    public record RuleEvaluation(String ruleId, long limit, long used,
                                 long remaining, boolean exceeded, boolean warnHit) {
    }
}
