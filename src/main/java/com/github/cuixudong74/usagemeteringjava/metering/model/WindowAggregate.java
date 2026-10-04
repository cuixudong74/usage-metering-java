package com.github.cuixudong74.usagemeteringjava.metering.model;

/** 单个窗口的聚合结果（不可变快照）。 */
public record WindowAggregate(WindowKey key, long total, long eventCount,
                              long lateEventCount, boolean closed) {
}
