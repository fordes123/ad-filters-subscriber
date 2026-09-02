package org.fordes.adfs.config;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonRootName("application")
public record ApplicationConfiguration(
        List<Source> input,
        Output output,
        BuildPlan.SourceLoadingPolicy sourceLoading,
        BuildPlan.ProcessingPolicy processing,
        BuildPlan.LoggingPolicy logging
) {

    public record Source(
            String name,
            String path,
            String type,
            String dialect,
            Integer priority
    ) {
    }

    public record Output(
            String path,
            @JsonProperty("file_header") String fileHeader,
            List<File> files
    ) {
    }

    public record File(
            String name,
            String type,
            String dialect,
            String desc,
            @JsonProperty("file_header") String fileHeader,
            @JsonProperty("rule") List<String> sources
    ) {
    }
}
