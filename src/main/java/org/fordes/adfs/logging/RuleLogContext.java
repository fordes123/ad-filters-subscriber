package org.fordes.adfs.logging;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.syntax.RuleFormat;

import java.util.Objects;

/**
 * 按既有日志格式渲染规则来源和目标。
 */
public final class RuleLogContext {

    private RuleLogContext() {
    }

    public static String source(BuildPlan.SourceSpec source) {
        Objects.requireNonNull(source, "source 不能为空");
        return ruleContext(source.id(), source.profile());
    }

    public static String source(RuleRecord record) {
        Objects.requireNonNull(record, "record 不能为空");
        return ruleContext(record.sourceId(), record.sourceProfile());
    }

    private static String ruleContext(String name, org.fordes.adfs.syntax.RuleProfile profile) {
        return switch (profile) {
            case org.fordes.adfs.syntax.RuleProfile.Adblock adblock ->
                    name + "(" + adblock.format().name + ", " + adblock.dialect().name + ")";
            case org.fordes.adfs.syntax.RuleProfile.Clash clash ->
                    name + "(" + RuleFormat.CLASH.name + ", " + clash.dialect().name + ")";
            case org.fordes.adfs.syntax.RuleProfile.Plain plain ->
                    name + "(" + plain.format().name + ")";
            case org.fordes.adfs.syntax.RuleProfile.SingBox ignored ->
                    name + "(" + RuleFormat.SING_BOX.name + ")";
        };
    }

    public static String output(BuildPlan.OutputSpec output) {
        Objects.requireNonNull(output, "output 不能为空");
        return ruleContext(output.path().getFileName().toString(), output.profile());
    }

}
