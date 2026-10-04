package com.example.metering.model;

import java.time.Instant;

/**
 * 窗口关闭后到达事件的可追溯标记。
 */
public record LateEventRecord(
        UsageEvent event,
        long windowStartEpochMs,
        long windowEndEpochMs,
        String policyApplied,
        Instant arrivedAt) {
}
