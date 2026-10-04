package com.example.metering.core;

/**
 * 窗口关闭后迟到事件的处理策略。
 */
public enum LateEventPolicy {
    /** 拒绝计入，仅留下标记。 */
    REJECT,
    /** 仍计入已关闭窗口，并留下标记。 */
    ACCUMULATE
}
