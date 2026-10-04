package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.model.IngestResult;
import com.github.cuixudong74.usagemeteringjava.metering.model.IngestStatus;
import com.github.cuixudong74.usagemeteringjava.metering.model.LateEventPolicy;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.model.WindowAggregate;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeteringStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MeteringStore newStore(LateEventPolicy policy) {
        MeteringStore store = new MeteringStore(Duration.ofHours(1), policy);
        store.setGracePeriod(Duration.ofHours(1));
        return store;
    }

    private static UsageEvent event(String id, String tenant, long qty, Instant time) {
        return new UsageEvent(id, tenant, "api_calls", qty, time);
    }

    @Test
    void duplicateEventIsCountedOnlyOnce() {
        MeteringStore store = newStore(LateEventPolicy.ACCEPT_AND_MARK);
        UsageEvent e = event("e1", "t1", 10, T0.plusSeconds(60));

        IngestResult first = store.ingest(e, T0);
        IngestResult second = store.ingest(e, T0.plusSeconds(1));

        assertThat(first.status()).isEqualTo(IngestStatus.ACCEPTED);
        assertThat(second.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(store.sumUsage("t1", "api_calls", T0, T0.plus(Duration.ofHours(1))))
                .isEqualTo(10);
        // 重复事件也可追溯
        assertThat(store.traceOf("e1")).isPresent();
    }

    @Test
    void outOfOrderEventsProduceSameResultAsOrdered() {
        List<UsageEvent> ordered = List.of(
                event("a", "t1", 1, T0.plusSeconds(10)),
                event("b", "t1", 2, T0.plusSeconds(3700)),   // 第二个窗口
                event("c", "t1", 4, T0.plusSeconds(20)),
                event("d", "t1", 8, T0.plusSeconds(3800)));

        MeteringStore inOrder = newStore(LateEventPolicy.ACCEPT_AND_MARK);
        ordered.forEach(e -> inOrder.ingest(e, T0.plusSeconds(4000)));

        MeteringStore shuffled = newStore(LateEventPolicy.ACCEPT_AND_MARK);
        List.of(ordered.get(2), ordered.get(0), ordered.get(3), ordered.get(1))
                .forEach(e -> shuffled.ingest(e, T0.plusSeconds(4000)));

        Instant from = T0, to = T0.plus(Duration.ofHours(3));
        assertThat(shuffled.query("t1", "api_calls", from, to))
                .isEqualTo(inOrder.query("t1", "api_calls", from, to));
    }

    @Test
    void lateEventLandsInItsOwnWindow() {
        MeteringStore store = newStore(LateEventPolicy.ACCEPT_AND_MARK);
        // 事件发生在第一个窗口，但上报时已进入第三个窗口
        store.ingest(event("late1", "t1", 5, T0.plusSeconds(30)), T0.plus(Duration.ofHours(2)));

        List<WindowAggregate> windows = store.query("t1", "api_calls", T0, T0.plus(Duration.ofHours(3)));
        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).key().windowStart()).isEqualTo(T0);
        assertThat(windows.get(0).total()).isEqualTo(5);
    }

    @Test
    void lateEventAfterWindowClosedIsMarkedAndCountedPerPolicy() {
        MeteringStore store = newStore(LateEventPolicy.ACCEPT_AND_MARK);
        // 窗口 [T0, T0+1h)，宽限 1h，now 超过 T0+2h 时窗口已关闭
        Instant now = T0.plus(Duration.ofHours(3));
        IngestResult result = store.ingest(event("late2", "t1", 7, T0.plusSeconds(10)), now);

        assertThat(result.status()).isEqualTo(IngestStatus.LATE_ACCEPTED);
        WindowAggregate w = store.query("t1", "api_calls", T0, T0.plus(Duration.ofHours(1))).get(0);
        assertThat(w.total()).isEqualTo(7);
        assertThat(w.lateEventCount()).isEqualTo(1);
        assertThat(store.traceOf("late2")).get().asString().contains("LATE_ACCEPTED");
    }

    @Test
    void lateEventAfterWindowClosedIsRejectedPerPolicy() {
        MeteringStore store = newStore(LateEventPolicy.REJECT_AND_MARK);
        Instant now = T0.plus(Duration.ofHours(3));
        IngestResult result = store.ingest(event("late3", "t1", 7, T0.plusSeconds(10)), now);

        assertThat(result.status()).isEqualTo(IngestStatus.LATE_REJECTED);
        assertThat(store.sumUsage("t1", "api_calls", T0, T0.plus(Duration.ofHours(1)))).isZero();
        // 拒绝也留痕
        assertThat(store.traceOf("late3")).get().asString().contains("LATE_REJECTED");
    }

    @Test
    void explicitlyClosedWindowRejectsOrMarksLateEvents() {
        MeteringStore store = newStore(LateEventPolicy.REJECT_AND_MARK);
        store.ingest(event("x", "t1", 1, T0.plusSeconds(5)), T0.plusSeconds(10));
        store.closeWindowsBefore(T0.plus(Duration.ofHours(1)));

        IngestResult result = store.ingest(event("y", "t1", 2, T0.plusSeconds(6)),
                T0.plusSeconds(20));
        assertThat(result.status()).isEqualTo(IngestStatus.LATE_REJECTED);
        assertThat(store.sumUsage("t1", "api_calls", T0, T0.plus(Duration.ofHours(1)))).isEqualTo(1);
    }
}
