package org.fordes.adfs.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.fordes.adfs.json.Json;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.RuleProfile;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static BuildPlan load(Path configPath) throws IOException {
        Objects.requireNonNull(configPath, "configPath 不能为空");
        if (!Files.isRegularFile(configPath)) {
            throw new IOException("配置文件不存在或不是普通文件: " + configPath);
        }
        try {
            return toBuildPlan(Json.readYaml(configPath, ApplicationConfiguration.class));
        } catch (JsonProcessingException error) {
            throw parseError(configPath, error);
        }
    }

    private static BuildPlan toBuildPlan(ApplicationConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("application 不能为空");
        }
        List<BuildPlan.SourceSpec> sources = sources(configuration.input());
        List<BuildPlan.OutputSpec> outputs = outputs(configuration.output());
        return new BuildPlan(
                sources,
                outputs,
                configuration.sourceLoading() == null
                        ? BuildPlan.SourceLoadingPolicy.defaults()
                        : configuration.sourceLoading(),
                configuration.processing() == null
                        ? BuildPlan.ProcessingPolicy.defaults()
                        : configuration.processing(),
                configuration.logging() == null
                        ? BuildPlan.LoggingPolicy.defaults()
                        : configuration.logging()
        );
    }

    private static List<BuildPlan.SourceSpec> sources(List<ApplicationConfiguration.Source> configurations) {
        if (configurations == null || configurations.isEmpty()) {
            throw new IllegalArgumentException("application.input 必须是非空 sequence");
        }
        List<BuildPlan.SourceSpec> sources = new ArrayList<>(configurations.size());
        for (int index = 0; index < configurations.size(); index++) {
            ApplicationConfiguration.Source source = require(
                    configurations.get(index),
                    "application.input[" + index + "]"
            );
            RuleProfile profile = profile(
                    source.type(), source.dialect(), "application.input[" + index + "]", true);
            sources.add(new BuildPlan.SourceSpec(
                    text(source.name(), "application.input[" + index + "].name"),
                    text(source.path(), "application.input[" + index + "].path"),
                    profile,
                    source.priority() == null ? 0 : source.priority()
            ));
        }
        return List.copyOf(sources);
    }

    private static List<BuildPlan.OutputSpec> outputs(ApplicationConfiguration.Output configuration) {
        ApplicationConfiguration.Output output = require(configuration, "application.output");
        if (output.files() == null || output.files().isEmpty()) {
            throw new IllegalArgumentException("application.output.files 必须是非空 sequence");
        }
        Path directory = Path.of(output.path() == null ? "rule" : output.path())
                .toAbsolutePath()
                .normalize();
        String globalHeader = output.fileHeader() == null ? "" : output.fileHeader();
        List<BuildPlan.OutputSpec> outputs = new ArrayList<>(output.files().size());
        for (int index = 0; index < output.files().size(); index++) {
            String location = "application.output.files[" + index + "]";
            ApplicationConfiguration.File file = require(output.files().get(index), location);
            RuleProfile profile = profile(file.type(), file.dialect(), location, false);
            String header = file.fileHeader() == null ? globalHeader : file.fileHeader();
            if (header.isBlank()) {
                header = globalHeader;
            }
            Path target = directory.resolve(text(file.name(), location + ".name")).normalize();
            if (!target.startsWith(directory)) {
                throw new IllegalArgumentException(location + ".name 不得超出输出目录");
            }
            outputs.add(new BuildPlan.OutputSpec(
                    target,
                    profile,
                    file.desc() == null ? "" : file.desc(),
                    header,
                    strings(file.sources(), location + ".rule")
            ));
        }
        return List.copyOf(outputs);
    }

    private static RuleProfile profile(
            String configuredFormat,
            String configuredDialect,
            String location,
            boolean input
    ) {
        RuleFormat format = RuleFormat.parse(
                configuredFormat == null && input ? RuleFormat.EASYLIST.name : text(
                        configuredFormat,
                        location + ".type"
                )
        );
        return switch (format) {
            case EASYLIST -> new RuleProfile.Adblock(
                    format,
                    DialectProfile.parse(configuredDialect == null ? DialectProfile.ABP.name : configuredDialect)
            );
            case DNS -> new RuleProfile.Adblock(
                    format,
                    DialectProfile.parse(configuredDialect == null ? DialectProfile.ADGUARD.name : configuredDialect)
            );
            case CLASH -> new RuleProfile.Clash(
                    ClashDialect.parse(configuredDialect == null
                            ? ClashDialect.CLASSICAL.name
                            : configuredDialect)
            );
            case HOSTS, DNSMASQ, SMARTDNS -> new RuleProfile.Plain(format);
            case SING_BOX -> new RuleProfile.SingBox();
        };
    }

    private static Set<String> strings(List<String> values, String location) {
        if (values == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            result.add(text(values.get(index), location + "[" + index + "]"));
        }
        return Set.copyOf(result);
    }

    private static <T> T require(T value, String location) {
        if (value == null) {
            throw new IllegalArgumentException(location + " 不能为空");
        }
        return value;
    }

    private static String text(String value, String location) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(location + " 必须是非空字符串");
        }
        return value;
    }

    private static IllegalArgumentException parseError(Path configPath, JsonProcessingException error) {
        JsonLocation location = error.getLocation();
        String prefix = location == null
                ? "配置文件 " + configPath + " 解析失败"
                : "配置文件 " + configPath + " 第 " + location.getLineNr() + " 行，第 "
                + location.getColumnNr() + " 列解析失败";
        return new IllegalArgumentException(prefix + ": " + error.getOriginalMessage(), error);
    }

}
