package com.example.metering.model;

import java.time.Instant;

/**
 * 租户上报的用量事件。eventId 全局唯一，用于幂等去重。
 */
public record UsageEvent(
        String eventId,
        String tenantId,
        String metric,
        long quantity,
        Instant eventTime) {
}
