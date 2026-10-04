package com.example.metering.model;

import java.time.Duration;

/**
 * 配额策略：按 (tenantId, metric) + 时间范围组合配置。
 */
public record QuotaPolicy(
        String tenantId,
        String metric,
        Duration period,
        long limit,
        double alertThresholdRatio,
        OverLimitStrategy overLimitStrategy) {

    public enum OverLimitStrategy {
        ALERT_ONLY,
        BLOCK
    }
}
