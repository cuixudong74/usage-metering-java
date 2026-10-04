package com.example.metering.core;

import com.example.metering.model.UsageEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 纯内存日志：不落盘，重启后中间状态直接丢弃。 */
public class InMemoryEventJournal implements EventJournal {

    private final List<UsageEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(UsageEvent event) {
        events.add(event);
    }

    @Override
    public List<UsageEvent> readAll() {
        return List.copyOf(events);
    }

    @Override
    public void reset() {
        events.clear();
    }
}
