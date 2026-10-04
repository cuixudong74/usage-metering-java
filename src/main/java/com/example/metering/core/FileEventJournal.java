package com.example.metering.core;

import com.example.metering.model.UsageEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件事件日志：每条事件追加一行 JSON，重启时全量回放重建聚合状态。
 * 追加失败会抛出异常，调用方据此决定拒绝上报，避免"已计入但未落盘"的不一致。
 */
public class FileEventJournal implements EventJournal {

    private final Path path;
    private final ObjectMapper mapper;

    public FileEventJournal(Path path) {
        this.path = path;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public synchronized void append(UsageEvent event) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(mapper.writeValueAsString(event));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append event to journal: " + path, e);
        }
    }

    @Override
    public synchronized List<UsageEvent> readAll() {
        List<UsageEvent> events = new ArrayList<>();
        if (!Files.exists(path)) {
            return events;
        }
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.filter(l -> !l.isBlank()).forEach(line -> {
                try {
                    events.add(mapper.readValue(line, UsageEvent.class));
                } catch (IOException e) {
                    throw new UncheckedIOException("corrupt journal line: " + line, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read journal: " + path, e);
        }
        return events;
    }

    @Override
    public synchronized void reset() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to reset journal: " + path, e);
        }
    }
}
