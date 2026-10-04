package com.github.cuixudong74.usagemeteringjava.metering.model;

import java.time.Instant;

/** 聚合窗口键：租户 + 维度 + 窗口起点。 */
public record WindowKey(String tenantId, String dimension, Instant windowStart) {
}
