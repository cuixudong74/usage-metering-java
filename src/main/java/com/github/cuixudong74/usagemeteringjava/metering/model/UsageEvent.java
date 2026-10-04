package com.github.cuixudong74.usagemeteringjava.metering.model;

import java.time.Instant;

/**
 * 租户上报的用量事件。
 *
 * @param eventId   事件唯一标识，用于幂等去重
 * @param tenantId  租户标识
 * @param dimension 用量维度（如 api_calls、storage_bytes）
 * @param quantity  用量数值，必须为正
 * @param eventTime 事件实际发生时间（决定归属窗口）
 */
public record UsageEvent(String eventId, String tenantId, String dimension,
                         long quantity, Instant eventTime) {
}
