package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.persist.SnapshotStore;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 计量生命周期管理：
 * - 启动时从快照恢复（恢复快照之后的在途状态被丢弃，可用回放重建）；
 * - 周期性落盘快照并关闭过期窗口；
 * - 停机前落盘一次，尽量缩小丢失窗口。
 */
@Component
public class MeteringLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MeteringLifecycle.class);

    private final MeteringStore store;
    private final SnapshotStore snapshotStore;
    private final Duration retention;

    public MeteringLifecycle(MeteringStore store, SnapshotStore snapshotStore,
                             @Value("${metering.retention:PT24H}") Duration retention) {
        this.store = store;
        this.snapshotStore = snapshotStore;
        this.retention = retention;
    }

    @PostConstruct
    public void restore() {
        snapshotStore.restore(store);
    }

    /** 周期任务：关闭过期窗口并落盘快照。 */
    @Scheduled(fixedDelayString = "${metering.snapshot-interval-ms:60000}")
    public void checkpoint() {
        int closed = store.closeWindowsBefore(Instant.now().minus(retention));
        if (closed > 0) {
            log.info("closed {} expired windows", closed);
        }
        snapshotStore.save(store);
    }

    @PreDestroy
    public void shutdown() {
        snapshotStore.save(store);
    }
}
