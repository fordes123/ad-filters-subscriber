package org.fordes.adfs.syntax;

import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.util.Objects;

/**
 * 一个可实际使用的格式与方言组合。
 *
 * <p>配置层以格式和方言表达目标；进入处理管线后通过本类型消除与格式无关的方言字段。</p>
 */
public sealed interface RuleProfile
        permits RuleProfile.Adblock, RuleProfile.Clash, RuleProfile.Plain, RuleProfile.SingBox {

    RuleFormat format();

    static RuleProfile of(
            RuleFormat format,
            DialectProfile dialect,
            ClashDialect clashDialect
    ) {
        Objects.requireNonNull(format, "format 不能为空");
        Objects.requireNonNull(dialect, "dialect 不能为空");
        Objects.requireNonNull(clashDialect, "clashDialect 不能为空");
        return switch (format) {
            case EASYLIST, DNS -> new Adblock(format, dialect);
            case CLASH -> new Clash(clashDialect);
            case HOSTS, DNSMASQ, SMARTDNS -> new Plain(format);
            case SING_BOX -> new SingBox();
        };
    }

    record Adblock(RuleFormat format, DialectProfile dialect) implements RuleProfile {
        public Adblock {
            Objects.requireNonNull(format, "format 不能为空");
            Objects.requireNonNull(dialect, "dialect 不能为空");
            if (format != RuleFormat.EASYLIST && format != RuleFormat.DNS) {
                throw new IllegalArgumentException("Adblock profile 只能用于 easylist 或 dns");
            }
        }
    }

    record Clash(ClashDialect dialect) implements RuleProfile {
        public Clash {
            Objects.requireNonNull(dialect, "clashDialect 不能为空");
        }

        @Override
        public RuleFormat format() {
            return RuleFormat.CLASH;
        }
    }

    record Plain(RuleFormat format) implements RuleProfile {
        public Plain {
            Objects.requireNonNull(format, "format 不能为空");
            if (format != RuleFormat.HOSTS
                    && format != RuleFormat.DNSMASQ
                    && format != RuleFormat.SMARTDNS) {
                throw new IllegalArgumentException("Plain profile 只能用于 hosts、dnsmasq 或 smartdns");
            }
        }
    }

    record SingBox() implements RuleProfile {
        @Override
        public RuleFormat format() {
            return RuleFormat.SING_BOX;
        }
    }
}
