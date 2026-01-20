package org.fordes.adfs.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import lombok.Data;
import org.apache.commons.codec.digest.MurmurHash3;
import org.fordes.adfs.config.InputProperties;
import org.fordes.adfs.enums.RuleSet;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * @author fordes123 on 2024/5/27
 */
@Data
public class Rule {

    /**
     * 规则来源名称，及 {@link InputProperties.Item#name()}
     */
    private String sourceName;

    /**
     * 规则来源类型 {@link RuleSet}
     */
    private RuleSet sourceType;

    /**
     * 原始规则
     */
    private String origin;

    /**
     * 作用目标
     */
    private String target;

    /**
     * 重定向/重写目标
     */
    private String dest;

    /**
     * 模式 {@link Mode}
     */
    private Mode mode;

    /**
     * 作用范围 {@link Scope}
     */
    private Scope scope;

    /**
     * 规则类型 {@link Type}
     */
    private Type type;

    /**
     * 控制符集 {@link Control}
     */
    private Set<Control> controls = new HashSet<>(Control.values().length, 1.0f);

    public static final Rule EMPTY = new Rule();

    public long murmur3Hash() {
        ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer(256);
        try {
            if (Type.UNKNOWN == this.type) {
                int len = buffer.writeCharSequence(this.origin, StandardCharsets.UTF_8);
                buffer.writeInt(len);
            } else {
                buffer.writeCharSequence(this.target, StandardCharsets.UTF_8);
                buffer.writeInt(this.mode.ordinal());
                buffer.writeInt(this.scope.ordinal());
                buffer.writeInt(this.type.ordinal());
            }

            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            long[] hash128x64 = MurmurHash3.hash128x64(bytes, 0, bytes.length, 0);
            return hash128x64[0];
        } finally {
            buffer.release();
        }
    }

    /**
     * 规则控制参数
     */
    public enum Control {
        /**
         * 最高优先级
         */
        IMPORTANT,

        /**
         * 覆盖子域名
         */
        OVERLAY,

        /**
         * 限定符，通常是 ^
         */
        QUALIFIER,

        ALL,
        ;
    }

    /**
     * 规则模式
     */
    public enum Mode {

        /**
         * 阻止
         */
        DENY,

        /**
         * 解除阻止
         */
        ALLOW,

        /**
         * 重写<br/>
         * 通常 hosts规则指向特定ip(非localhost)时即为重写
         */
        REWRITE,

        ;
    }

    /**
     * 规则类型
     */
    public enum Type {
        /**
         * 基本规则，不包含任何控制、匹配符号, 可以转换为 hosts
         */
        BASIC,

        /**
         * 通配规则，仅使用通配符
         */
        WILDCARD,


        REGEX,

        /**
         * 其他规则，如使用了正则、高级修饰符号等，这表示目前无法支持
         */
        UNKNOWN,

        ;
    }

    /**
     * 作用域
     */
    public enum Scope {
        /**
         * ipv4或ipv6地址
         */
        HOST,

        /**
         * 域名
         */
        DOMAIN,

        /**
         * 路径、文件等
         */
        PATH,

        ;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof Rule rule) {
            if (Type.UNKNOWN == this.type || Type.UNKNOWN == rule.getType()) {
                return Objects.equals(this.origin, rule.origin);
            }
            return Objects.equals(this.target, rule.target) &&
                    this.mode == rule.mode &&
                    this.scope == rule.scope &&
                    this.type == rule.type;
        }
        return false;
    }

    @Override
    public int hashCode() {
        if (Type.UNKNOWN == this.type) {
            return Objects.hash(this.origin);
        }
        return Objects.hash(getTarget(), getMode(), getScope(), getType());
    }

    @Override
    public String toString() {
        return "Rule{" +
                "origin='" + origin + '\'' +
                ", target='" + target + '\'' +
                ", dest='" + dest + '\'' +
                ", mode=" + mode +
                ", scope=" + scope +
                ", type=" + type +
                ", controls=" + controls +
                '}';
    }
}