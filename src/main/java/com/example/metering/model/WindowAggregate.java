package com.example.metering.model;

/**
 * 单个时间窗口的聚合结果。
 */
public record WindowAggregate(
        String tenantId,
        String metric,
        long windowStartEpochMs,
        long windowEndEpochMs,
        long totalQuantity,
        long eventCount,
        boolean closed) {
}
