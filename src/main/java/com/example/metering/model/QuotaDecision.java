package com.example.metering.model;

import java.time.Instant;

/**
 * 配额判定结果：给出命中的配额、剩余额度与可解释原因。
 */
public record QuotaDecision(
        String tenantId,
        String metric,
        boolean allowed,
        boolean alertTriggered,
        long currentUsage,
        long limit,
        long remaining,
        String matchedQuotaKey,
        String reason,
        Instant decidedAt) {
}
