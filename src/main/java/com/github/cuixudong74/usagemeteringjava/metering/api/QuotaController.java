package com.github.cuixudong74.usagemeteringjava.metering.api;

import com.github.cuixudong74.usagemeteringjava.metering.MeteringService;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaConfig;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaDecision;
import com.github.cuixudong74.usagemeteringjava.metering.quota.QuotaRule;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 配额接口：判定与规则热更新。 */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final MeteringService meteringService;
    private final QuotaConfig quotaConfig;

    public QuotaController(MeteringService meteringService, QuotaConfig quotaConfig) {
        this.meteringService = meteringService;
        this.quotaConfig = quotaConfig;
    }

    @GetMapping("/evaluate")
    public QuotaDecision evaluate(@RequestParam String tenantId,
                                  @RequestParam String dimension,
                                  @RequestParam(defaultValue = "1") long quantity) {
        return meteringService.checkQuota(tenantId, dimension, quantity);
    }

    @GetMapping("/rules")
    public List<QuotaRule> rules() {
        return quotaConfig.rules();
    }

    @PutMapping("/rules")
    public List<QuotaRule> replaceRules(@RequestBody List<QuotaRule> rules) {
        quotaConfig.replaceRules(rules);
        return quotaConfig.rules();
    }
}
