package com.example.metering.model;

import java.util.List;

/**
 * 回放对账报告：重算结果与当前状态的差异及分类。
 */
public record ReconcileReport(
        int batchSize,
        int diffCount,
        List<DiffEntry> diffs) {

    public record DiffEntry(
            DiffType type,
            String eventId,
            String detail) {
    }

    public enum DiffType {
        DUPLICATE_IGNORED,
        MISSING_IN_CURRENT,
        QUANTITY_MISMATCH,
        LATE_TO_CLOSED_WINDOW,
        EXTRA_IN_CURRENT
    }
}
