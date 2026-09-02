package org.fordes.adfs.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 一次构建的不可变结果摘要。 */
public record BuildReport(
        List<Source> sources,
        List<Output> outputs,
        Duration elapsed
) {

    public BuildReport {
        sources = List.copyOf(sources);
        outputs = List.copyOf(outputs);
        Objects.requireNonNull(elapsed, "elapsed 不能为空");
    }

    public long invalidRules() {
        return sources.stream().mapToLong(Source::invalid).sum();
    }

    public record Source(String sourceId, long parsed, long invalid) {
    }

    public record Output(Path path, long approximations, long unsupported, long finalRules) {
    }
}
