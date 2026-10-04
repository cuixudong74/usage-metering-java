package com.example.metering.core;

import com.example.metering.model.UsageEvent;

import java.util.List;

/**
 * 事件日志：追加落盘，重启时回放，保证未落盘中间状态可重建。
 */
public interface EventJournal {

    void append(UsageEvent event);

    List<UsageEvent> readAll();

    void reset();
}
