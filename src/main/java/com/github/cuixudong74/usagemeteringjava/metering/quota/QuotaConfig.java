package com.github.cuixudong74.usagemeteringjava.metering.quota;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配额配置持有者，支持运行中热更新。
 * 更新通过 AtomicReference 整体替换：任何一次判定要么读到旧版本、
 * 要么读到新版本，绝不会读到替换过程中的中间状态；
 * 已返回的判定结果是不可变记录，不会因配置变更而回退。
 */
public class QuotaConfig {

    private final AtomicReference<List<QuotaRule>> rules =
            new AtomicReference<>(List.of());

    public List<QuotaRule> rules() {
        return rules.get();
    }

    public void replaceRules(List<QuotaRule> newRules) {
        rules.set(List.copyOf(newRules));
    }
}
