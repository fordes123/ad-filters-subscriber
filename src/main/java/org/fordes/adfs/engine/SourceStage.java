package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;

import java.nio.file.Path;
import java.util.Objects;

record SourceStage(
        BuildPlan.SourceSpec source,
        Path segment,
        BuildReport.Source report
) {

    SourceStage {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(segment, "segment 不能为空");
        Objects.requireNonNull(report, "report 不能为空");
    }
}
