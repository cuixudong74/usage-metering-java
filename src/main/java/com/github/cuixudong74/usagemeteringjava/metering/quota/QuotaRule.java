package com.github.cuixudong74.usagemeteringjava.metering.quota;

import java.time.Duration;

/**
 * 配额规则：按用量维度 + 时间范围组合配置。
 *
 * @param ruleId        规则标识
 * @param tenantId      适用租户，"*" 表示全部
 * @param dimension     用量维度，"*" 表示全部
 * @param period        统计周期长度（从周期起点对齐）
 * @param limit         限额
 * @param warnThreshold 告警阈值（0~1 的比例）
 * @param blockOnExceed 超限是否拦截
 */
public record QuotaRule(String ruleId, String tenantId, String dimension,
                        Duration period, long limit, double warnThreshold,
                        boolean blockOnExceed) {
}
