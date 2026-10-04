package com.github.cuixudong74.usagemeteringjava.metering.model;

import java.time.Instant;

/**
 * 单次事件摄入结果。
 *
 * @param status      摄入状态
 * @param windowStart 事件归属（或本应归属）的窗口起点
 * @param traceId     可追溯标记，迟到/重复事件也可凭此查询处理记录
 */
public record IngestResult(IngestStatus status, Instant windowStart, String traceId) {
}
