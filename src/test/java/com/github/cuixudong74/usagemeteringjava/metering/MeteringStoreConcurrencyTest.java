package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.model.LateEventPolicy;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.model.WindowAggregate;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MeteringStoreConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void concurrentIngestForSameTenantHasNoLostUpdates() throws Exception {
        MeteringStore store = new MeteringStore(Duration.ofHours(1), LateEventPolicy.ACCEPT_AND_MARK);
        int threads = 8, perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.ingest(new UsageEvent("e" + (base + i), "t1", "api_calls", 1,
                                T0.plusSeconds((base + i) % 3000)), T0);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(store.sumUsage("t1", "api_calls", T0, T0.plus(Duration.ofHours(1))))
                .isEqualTo((long) threads * perThread);
    }

    @Test
    void concurrentQueryNeverReadsPartialUpdate() throws Exception {
        MeteringStore store = new MeteringStore(Duration.ofHours(1), LateEventPolicy.ACCEPT_AND_MARK);
        // 每个事件 quantity=2，若能读到中间状态，可能出现奇数总量
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean sawInconsistent = new AtomicBoolean(false);

        Thread writer = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                store.ingest(new UsageEvent("w" + i, "t1", "api_calls", 2,
                        T0.plusSeconds(i % 3000)), T0);
                i++;
            }
        });
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                List<WindowAggregate> windows =
                        store.query("t1", "api_calls", T0, T0.plus(Duration.ofHours(1)));
                long total = windows.stream().mapToLong(WindowAggregate::total).sum();
                long count = windows.stream().mapToLong(WindowAggregate::eventCount).sum();
                if (total != count * 2) {
                    sawInconsistent.set(true);
                }
            }
        });
        writer.start();
        reader.start();
        Thread.sleep(2000);
        stop.set(true);
        writer.join();
        reader.join();

        assertThat(sawInconsistent).isFalse();
    }

    @Test
    void concurrentDuplicateIngestCountsOnce() throws Exception {
        MeteringStore store = new MeteringStore(Duration.ofHours(1), LateEventPolicy.ACCEPT_AND_MARK);
        UsageEvent e = new UsageEvent("dup", "t1", "api_calls", 5, T0.plusSeconds(1));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    store.ingest(e, T0);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(store.sumUsage("t1", "api_calls", T0, T0.plus(Duration.ofHours(1)))).isEqualTo(5);
    }
}
