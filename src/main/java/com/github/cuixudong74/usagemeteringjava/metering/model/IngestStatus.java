package com.github.cuixudong74.usagemeteringjava.metering.model;

/** 事件摄入结果状态。 */
public enum IngestStatus {
    /** 正常计入对应窗口 */
    ACCEPTED,
    /** 重复事件，未重复计入 */
    DUPLICATE,
    /** 窗口已关闭，按策略计入但带迟到标记 */
    LATE_ACCEPTED,
    /** 窗口已关闭，按策略拒绝并留痕 */
    LATE_REJECTED
}
