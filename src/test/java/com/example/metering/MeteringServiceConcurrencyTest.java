package com.example.metering;

import com.example.metering.config.MeteringProperties;
import com.example.metering.core.InMemoryEventJournal;
import com.example.metering.core.MeteringService;
import com.example.metering.core.WindowAggregator;
import com.example.metering.model.UsageEvent;
import com.example.metering.model.WindowAggregate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeteringServiceConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MeteringService newService() {
        MeteringProperties props = new MeteringProperties();
        return new MeteringService(props, new InMemoryEventJournal());
    }

    @Test
    void concurrentReportsFromSameTenantLoseNoUpdates() throws Exception {
        MeteringService service = newService();
        int threads = 8, perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            futures.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    service.report(new UsageEvent("t" + thread + "-e" + i, "tenant-x",
                            "api-calls", 1, T0.plusSeconds(i)));
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<WindowAggregate> windows = service.query("tenant-x", "api-calls");
        long total = windows.stream().mapToLong(WindowAggregate::totalQuantity).sum();
        long count = windows.stream().mapToLong(WindowAggregate::eventCount).sum();
        assertEquals(threads * perThread, total);
        assertEquals(threads * perThread, count);
    }

    @Test
    void concurrentDuplicateSubmissionsCountedOnce() throws Exception {
        MeteringService service = newService();
        UsageEvent event = new UsageEvent("dup-1", "tenant-x", "api-calls", 7, T0);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                if (service.report(event) == WindowAggregator.IngestResult.APPLIED) {
                    applied.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, applied.get());
        assertEquals(7, service.query("tenant-x", "api-calls").get(0).totalQuantity());
    }

    @Test
    void concurrentQueriesNeverReadIntermediateState() throws Exception {
        MeteringService service = newService();
        int threads = 4, perThread = 300;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            futures.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    service.report(new UsageEvent("w" + thread + "-" + i, "tenant-x",
                            "api-calls", 1, T0.plusSeconds(i)));
                }
            }));
        }
        // 并发查询：每次读到的快照必须自洽（quantity=1 时 total == count）且单调不回退
        futures.add(pool.submit(() -> {
            await(start);
            long lastTotal = -1;
            for (int i = 0; i < 500; i++) {
                List<WindowAggregate> snapshot = service.query("tenant-x", "api-calls");
                long total = snapshot.stream().mapToLong(WindowAggregate::totalQuantity).sum();
                long count = snapshot.stream().mapToLong(WindowAggregate::eventCount).sum();
                assertEquals(total, count, "torn read: total != count");
                assertTrue(total >= lastTotal, "read regressed: " + lastTotal + " -> " + total);
                lastTotal = total;
            }
        }));
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
