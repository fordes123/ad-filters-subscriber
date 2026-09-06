package dev.fordes.adfs.report;

import jakarta.inject.Singleton;

import dev.fordes.adfs.application.ProcessingResult;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public final class RunReporter {

    public void report(ProcessingResult result) {
        long totalRules = Math.addExact(result.semanticRules(), result.opaqueRules());
        double rulesPerSecond = result.elapsedMillis() == 0
                ? totalRules
                : totalRules * 1_000.0 / result.elapsedMillis();
        result.outputs().forEach((path, metrics) -> {
            log.info("目标「{}」处理完成，写入 {} 条，透传 {} 条，去重移除 {} 条，白名单移除 {} 条，未转换 {} 条",
                    path, metrics.written(), metrics.passthrough(), metrics.duplicates(), metrics.whitelistRemoved(),
                    metrics.unsupported());
            if (metrics.unsupported() > 0) {
                log.warn("目标「{}」有 {} 条规则未转换，目标格式不支持或转换策略不允许，具体原因见 DEBUG 日志",
                        path, metrics.unsupported());
            }
        });
        log.info("DNS 统计: 检测 {} 条，有效 {} 条，无效 {} 条，跳过 {} 条，合并 {} 次，重试 {} 次，缓存 {} 项",
                result.dns().checked(), result.dns().valid(), result.dns().invalid(), result.dns().skipped(),
                result.dns().merged(), result.dns().retries(), result.dns().cacheEntries());
        log.debug("去重哈希表: {} 个条目，容量 {}，碰撞 {} 次", result.hashEntries(), result.hashCapacity(),
                result.hashCollisions());
        log.info("处理完成，输入源 {} 个，输出目标 {} 个，解析 {} 条语义规则和 {} 条透传规则，"
                        + "去重后 {} 条，重复 {} 条，耗时 {} ms，平均每秒 {} 条",
                result.inputs(), result.outputs().size(), result.semanticRules(), result.opaqueRules(),
                result.uniqueRules(), result.duplicateRules(), result.elapsedMillis(), Math.round(rulesPerSecond));
    }
}
