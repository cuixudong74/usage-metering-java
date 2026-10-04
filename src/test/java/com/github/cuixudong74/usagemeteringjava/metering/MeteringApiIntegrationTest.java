package com.github.cuixudong74.usagemeteringjava.metering;

import com.github.cuixudong74.usagemeteringjava.metering.model.IngestResult;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.model.WindowAggregate;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaDecision;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaRule;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 端到端：通过本地 HTTP 接口走完 上报 → 查询 → 配额 → 热更新 → 回放 链路。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "metering.window-size=PT1H",
                "metering.late-policy=ACCEPT_AND_MARK",
                "metering.snapshot-dir=${java.io.tmpdir}/metering-it-${random.uuid}",
                "metering.snapshot-interval-ms=3600000"
        })
class MeteringApiIntegrationTest {

    // 用接近当前的时间，避免事件落入已关闭窗口
    private static final Instant T0 = Instant.now().minusSeconds(60);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void endToEndFlow() {
        // 1. 配置配额规则（热更新接口）
        QuotaRule rule = new QuotaRule("r1", "it-tenant", "api_calls",
                Duration.ofDays(1), 100, 0.8, true);
        rest.put(url("/api/quota/rules"), List.of(rule));

        // 2. 上报事件（含一次重复提交）
        UsageEvent e1 = new UsageEvent("it-e1", "it-tenant", "api_calls", 50, T0.plusSeconds(10));
        IngestResult r1 = rest.postForObject(url("/api/metering/events"), e1, IngestResult.class);
        IngestResult r2 = rest.postForObject(url("/api/metering/events"), e1, IngestResult.class);
        assertThat(r1.status().name()).isEqualTo("ACCEPTED");
        assertThat(r2.status().name()).isEqualTo("DUPLICATE");

        // 3. 查询窗口聚合：重复事件未重复计入
        WindowAggregate[] windows = rest.getForObject(
                url("/api/metering/windows?tenantId=it-tenant&dimension=api_calls&from="
                        + T0 + "&to=" + T0.plus(Duration.ofHours(2))),
                WindowAggregate[].class);
        assertThat(windows).hasSize(1);
        assertThat(windows[0].total()).isEqualTo(50);

        // 4. 配额判定：命中规则并给出剩余额度
        QuotaDecision decision = rest.getForObject(
                url("/api/quota/evaluate?tenantId=it-tenant&dimension=api_calls&quantity=40"),
                QuotaDecision.class);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.matchedRules()).hasSize(1);
        assertThat(decision.matchedRules().get(0).remaining()).isEqualTo(10);

        // 5. 阈值热更新：0.8 -> 0.4，运行中生效
        rest.put(url("/api/quota/rules"), List.of(new QuotaRule("r1", "it-tenant", "api_calls",
                Duration.ofDays(1), 100, 0.4, true)));
        QuotaDecision after = rest.getForObject(
                url("/api/quota/evaluate?tenantId=it-tenant&dimension=api_calls&quantity=0"),
                QuotaDecision.class);
        assertThat(after.warning()).isTrue();

        // 6. 回放对账：权威批次与线上一致，无差异
        ReplayReport report = rest.postForObject(url("/api/replay"), List.of(e1),
                ReplayReport.class);
        assertThat(report.totalEvents()).isEqualTo(1);
        assertThat(report.diffs()).isEmpty();
    }
}
