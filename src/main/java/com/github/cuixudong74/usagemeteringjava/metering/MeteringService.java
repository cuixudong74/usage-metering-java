package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.model.IngestResult;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.model.WindowAggregate;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaDecision;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaService;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 计量门面：组合摄入、查询与配额判定。 */
public class MeteringService {

    private final MeteringStore store;
    private final QuotaService quotaService;
    private final Clock clock;

    public MeteringService(MeteringStore store, QuotaService quotaService) {
        this(store, quotaService, Clock.systemUTC());
    }

    public MeteringService(MeteringStore store, QuotaService quotaService, Clock clock) {
        this.store = store;
        this.quotaService = quotaService;
        this.clock = clock;
    }

    /** 上报用量事件：幂等、乱序安全，迟到事件按策略处理并可追溯。 */
    public IngestResult report(UsageEvent event) {
        return store.ingest(event, clock.instant());
    }

    /** 按窗口查询计量结果。 */
    public List<WindowAggregate> query(String tenantId, String dimension, Instant from, Instant to) {
        return store.query(tenantId, dimension, from, to);
    }

    /** 查询事件处理痕迹（重复/迟到/被拒均可追溯）。 */
    public java.util.Optional<String> traceOf(String eventId) {
        return store.traceOf(eventId);
    }

    /** 配额判定：返回命中的配额规则与剩余额度。 */
    public QuotaDecision checkQuota(String tenantId, String dimension, long quantity) {
        return quotaService.evaluate(tenantId, dimension, quantity, clock.instant());
    }
}
