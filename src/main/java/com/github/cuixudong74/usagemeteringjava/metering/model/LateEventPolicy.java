package com.github.cuixudong74.usagemeteringjava.metering.model;

/** 窗口关闭后迟到事件的处理策略。 */
public enum LateEventPolicy {
    /** 仍计入原窗口，但打迟到标记 */
    ACCEPT_AND_MARK,
    /** 拒绝计入，仅留痕 */
    REJECT_AND_MARK
}
