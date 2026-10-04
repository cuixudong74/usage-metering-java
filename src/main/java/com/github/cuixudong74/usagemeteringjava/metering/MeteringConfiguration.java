package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.model.LateEventPolicy;
import com.github.cuixudong74.usagemeteringjava.metering.persist.SnapshotStore;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaConfig;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaService;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayService;
import com.github.cuixudong74.usagemeteringjava.metering.store.MeteringStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/** 计量链路装配。 */
@Configuration
public class MeteringConfiguration {

    @Bean
    public MeteringStore meteringStore(
            @Value("${metering.window-size:PT1H}") Duration windowSize,
            @Value("${metering.late-policy:ACCEPT_AND_MARK}") LateEventPolicy latePolicy,
            @Value("${metering.grace-period:PT1H}") Duration gracePeriod) {
        MeteringStore store = new MeteringStore(windowSize, latePolicy);
        store.setGracePeriod(gracePeriod);
        return store;
    }

    @Bean
    public QuotaConfig quotaConfig() {
        return new QuotaConfig();
    }

    @Bean
    public QuotaService quotaService(MeteringStore store, QuotaConfig config) {
        return new QuotaService(store, config);
    }

    @Bean
    public MeteringService meteringService(MeteringStore store, QuotaService quotaService) {
        return new MeteringService(store, quotaService);
    }

    @Bean
    public ReplayService replayService(MeteringStore store) {
        return new ReplayService(store);
    }

    @Bean
    public SnapshotStore snapshotStore(@Value("${metering.snapshot-dir:./data/snapshots}") Path dir) {
        return new SnapshotStore(dir);
    }
}
