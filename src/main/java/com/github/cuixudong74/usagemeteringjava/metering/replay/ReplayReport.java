package com.github.cuixudong74.usagemeteringjava.metering.replay;

import java.util.List;
import java.util.Map;

/**
 * 批量回放对账报告。
 *
 * @param totalEvents    回放事件总数
 * @param diffs          重算前后存在差异的窗口明细
 * @param diffByCategory 差异原因分类计数
 */
public record ReplayReport(int totalEvents, List<WindowDiff> diffs,
                           Map<DiffCategory, Long> diffByCategory) {

    /** 差异原因分类。 */
    public enum DiffCategory {
        /** 原结果中缺失（事件当时未计入，如迟到被拒） */
        MISSING_EVENT,
        /** 数值不一致（如重复计入或部分丢失） */
        VALUE_MISMATCH,
        /** 无差异 */
        NONE
    }

    /** 单窗口差异明细。 */
    public record WindowDiff(String tenantId, String dimension, String windowStart,
                             long beforeTotal, long afterTotal, DiffCategory category) {
    }
}
