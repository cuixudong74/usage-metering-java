package com.github.cuixudong74.usagemeteringjava.metering.persist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.cuixudong74.usagemeteringjava.metering.model.WindowAggregate;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * 快照持久化：把窗口聚合与事件处理痕迹整体落盘，重启时恢复。
 *
 * 语义：
 * - 落盘是原子的（先写临时文件再 rename），不会留下半份快照；
 * - 快照之后的在途状态在重启时丢弃，需要时可通过回放（ReplayService）重建；
 * - 恢复只发生在启动阶段（无并发写入），恢复后计量与配额消耗自洽：
 *   配额消耗由窗口聚合推导，痕迹表保证已处理事件不会被重复计入。
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);
    private static final String FILE_NAME = "metering-snapshot.json";

    /** 落盘的完整状态。 */
    public record Snapshot(Duration windowSize, Duration gracePeriod,
                           List<WindowAggregate> windows,
                           List<MeteringStore.TraceRecord> traces) {
    }

    private final Path dir;
    private final ObjectMapper mapper;

    public SnapshotStore(Path dir) {
        this.dir = dir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** 将当前状态原子落盘。 */
    public void save(MeteringStore store) {
        try {
            Files.createDirectories(dir);
            Snapshot snapshot = new Snapshot(store.windowSize(), store.gracePeriod(),
                    store.snapshotAggregates(), store.snapshotTraces());
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            mapper.writeValue(tmp.toFile(), snapshot);
            Files.move(tmp, dir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.info("snapshot saved: {} windows, {} traces",
                    snapshot.windows().size(), snapshot.traces().size());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save snapshot", e);
        }
    }

    /**
     * 启动时恢复到 store；无快照则什么都不做（从空状态开始）。
     *
     * @return true 表示恢复快照成功
     */
    public boolean restore(MeteringStore store) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            log.info("no snapshot found at {}, starting empty", file);
            return false;
        }
        try {
            Snapshot snapshot = mapper.readValue(file.toFile(), Snapshot.class);
            if (!snapshot.windowSize().equals(store.windowSize())) {
                // 窗口大小配置变了，旧快照无法解释，丢弃以避免错乱
                log.warn("snapshot windowSize {} != configured {}, discarding snapshot",
                        snapshot.windowSize(), store.windowSize());
                return false;
            }
            store.setGracePeriod(snapshot.gracePeriod());
            store.loadAggregates(snapshot.windows());
            store.loadTraces(snapshot.traces());
            log.info("snapshot restored: {} windows, {} traces",
                    snapshot.windows().size(), snapshot.traces().size());
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to restore snapshot", e);
        }
    }
}
