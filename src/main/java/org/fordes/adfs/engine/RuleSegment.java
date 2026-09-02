package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.ast.NetworkAction;
import org.fordes.adfs.ast.NetworkAnchor;
import org.fordes.adfs.model.AdblockExtendedRule;
import org.fordes.adfs.model.CanonicalRule;
import org.fordes.adfs.model.RuleBody;
import org.fordes.adfs.model.RuleRecord;
import org.fordes.adfs.syntax.RuleFormat;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import org.fordes.adfs.syntax.clash.ClashDialect;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class RuleSegment {

    private static final int MAGIC = 0x41444653;
    private static final int VERSION = 4;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int RECORD = 1;
    private static final int END = 0;
    private static final int BODY_CANONICAL = 1;
    private static final int BODY_ADBLOCK_NETWORK = 2;
    private static final int BODY_EXTENDED = 3;
    private static final int BODY_OPAQUE = 4;
    private static final int MAX_MODIFIERS = 1_000_000;

    private RuleSegment() {
    }

    static Writer writer(Path path, BuildPlan.SourceSpec source) throws IOException {
        return new Writer(path, source);
    }

    static Reader reader(Path path) throws IOException {
        return new Reader(path);
    }

    static final class Writer implements AutoCloseable {

        private final DataOutputStream output;
        private final BuildPlan.SourceSpec source;
        private boolean closed;

        private Writer(Path path, BuildPlan.SourceSpec source) throws IOException {
            Objects.requireNonNull(path, "path 不能为空");
            this.source = Objects.requireNonNull(source, "source 不能为空");
            output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(path), BUFFER_SIZE));
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeString(output, source.id());
            output.writeByte(source.format().ordinal());
            output.writeByte(source.dialect().ordinal());
            output.writeByte(source.clashDialect().ordinal());
        }

        void write(RuleRecord record) throws IOException {
            Objects.requireNonNull(record, "record 不能为空");
            if (closed) {
                throw new IOException("规则段已经关闭");
            }
            if (!record.sourceId().equals(source.id())
                    || !record.sourceProfile().equals(source.profile())) {
                throw new IOException("规则与规则段来源元数据不一致: source=" + source.id());
            }
            output.writeByte(RECORD);
            writeString(output, record.raw());
            writeBody(output, record.body());
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            output.writeByte(END);
            output.close();
        }
    }

    static final class Reader implements AutoCloseable {

        private final Path path;
        private final DataInputStream input;
        private final String sourceId;
        private final RuleFormat format;
        private final DialectProfile dialect;
        private final ClashDialect clashDialect;
        private boolean ended;

        private Reader(Path path) throws IOException {
            this.path = Objects.requireNonNull(path, "path 不能为空");
            input = new DataInputStream(new BufferedInputStream(
                    Files.newInputStream(path), BUFFER_SIZE));
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != VERSION) {
                input.close();
                throw new IOException("规则段格式无效: " + path);
            }
            sourceId = readString(input, path);
            format = readEnum(input, RuleFormat.values(), "format", path);
            dialect = readEnum(input, DialectProfile.values(), "dialect", path);
            clashDialect = readEnum(input, ClashDialect.values(), "clashDialect", path);
        }

        RuleRecord read() throws IOException {
            if (ended) {
                return null;
            }
            final int marker;
            try {
                marker = input.readUnsignedByte();
            } catch (EOFException error) {
                throw new IOException("规则段缺少结束标记: " + path, error);
            }
            if (marker == END) {
                ended = true;
                return null;
            }
            if (marker != RECORD) {
                throw new IOException("规则段包含未知记录标记: path=" + path + ", marker=" + marker);
            }

            String raw = readString(input, path);
            RuleBody body = readBody(input, path);
            return new RuleRecord(
                    sourceId,
                    org.fordes.adfs.syntax.RuleProfile.of(format, dialect, clashDialect),
                    raw,
                    body
            );
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static CanonicalRule readCanonical(DataInputStream input, Path path) throws IOException {
        CanonicalRule.MatchType matchType = readEnum(
                input, CanonicalRule.MatchType.values(), "matchType", path);
        CanonicalRule.Action action = readEnum(
                input, CanonicalRule.Action.values(), "action", path);
        long featureMask = input.readLong();
        String value = readString(input, path);
        Optional<String> destination = input.readBoolean()
                ? Optional.of(readString(input, path))
                : Optional.empty();
        return new CanonicalRule(matchType, value, action, destination, featureMask);
    }

    private static void writeBody(DataOutputStream output, RuleBody body) throws IOException {
        switch (body) {
            case RuleBody.Canonical canonical -> {
                output.writeByte(BODY_CANONICAL);
                writeCanonical(output, canonical.value());
            }
            case RuleBody.AdblockNetwork network -> {
                output.writeByte(BODY_ADBLOCK_NETWORK);
                output.writeByte(network.action().ordinal());
                output.writeByte(network.leftAnchor().ordinal());
                output.writeBoolean(network.rightAnchor());
                output.writeBoolean(network.regex());
                writeString(output, network.prefix());
                writeString(output, network.pattern());
                writeString(output, network.suffix());
                output.writeInt(network.modifiers().size());
                for (RuleBody.AdblockNetwork.Modifier modifier : network.modifiers()) {
                    writeString(output, modifier.source());
                    writeString(output, modifier.name());
                    output.writeBoolean(modifier.value().isPresent());
                    if (modifier.value().isPresent()) {
                        writeString(output, modifier.value().orElseThrow());
                    }
                    output.writeBoolean(modifier.negated());
                }
                output.writeBoolean(network.portable().isPresent());
                if (network.portable().isPresent()) {
                    writeCanonical(output, network.portable().orElseThrow());
                }
            }
            case RuleBody.Extended extended -> {
                output.writeByte(BODY_EXTENDED);
                writeExtended(output, extended.value());
            }
            case RuleBody.Opaque opaque -> {
                output.writeByte(BODY_OPAQUE);
                writeString(output, opaque.kind());
            }
        }
    }

    private static RuleBody readBody(DataInputStream input, Path path) throws IOException {
        int tag = input.readUnsignedByte();
        return switch (tag) {
            case BODY_CANONICAL -> new RuleBody.Canonical(readCanonical(input, path));
            case BODY_ADBLOCK_NETWORK -> readAdblockNetwork(input, path);
            case BODY_EXTENDED -> new RuleBody.Extended(readExtended(input, path));
            case BODY_OPAQUE -> new RuleBody.Opaque(readString(input, path));
            default -> throw new IOException("规则段包含未知规则体标记: path=" + path + ", tag=" + tag);
        };
    }

    private static RuleBody.AdblockNetwork readAdblockNetwork(
            DataInputStream input,
            Path path
    ) throws IOException {
        NetworkAction action = readEnum(
                input, NetworkAction.values(), "network.action", path);
        NetworkAnchor leftAnchor = readEnum(
                input, NetworkAnchor.values(), "network.leftAnchor", path);
        boolean rightAnchor = input.readBoolean();
        boolean regex = input.readBoolean();
        String prefix = readString(input, path);
        String pattern = readString(input, path);
        String suffix = readString(input, path);
        int modifierCount = input.readInt();
        if (modifierCount < 0 || modifierCount > MAX_MODIFIERS) {
            throw new IOException("规则段 modifier 数量无效: path=" + path + ", count=" + modifierCount);
        }
        List<RuleBody.AdblockNetwork.Modifier> modifiers = new ArrayList<>(modifierCount);
        for (int index = 0; index < modifierCount; index++) {
            String source = readString(input, path);
            String name = readString(input, path);
            Optional<String> value = input.readBoolean()
                    ? Optional.of(readString(input, path))
                    : Optional.empty();
            boolean negated = input.readBoolean();
            modifiers.add(new RuleBody.AdblockNetwork.Modifier(source, name, value, negated));
        }
        Optional<CanonicalRule> portable = input.readBoolean()
                ? Optional.of(readCanonical(input, path))
                : Optional.empty();
        return new RuleBody.AdblockNetwork(
                action, leftAnchor, rightAnchor, regex, prefix, pattern, suffix, modifiers, portable);
    }

    private static void writeCanonical(DataOutputStream output, CanonicalRule canonical)
            throws IOException {
        output.writeByte(canonical.matchType().ordinal());
        output.writeByte(canonical.action().ordinal());
        output.writeLong(canonical.featureMask());
        writeString(output, canonical.value());
        output.writeBoolean(canonical.destination().isPresent());
        if (canonical.destination().isPresent()) {
            writeString(output, canonical.destination().orElseThrow());
        }
    }

    private static void writeExtended(
            DataOutputStream output,
            AdblockExtendedRule rule
    ) throws IOException {
        output.writeByte(rule.syntax().ordinal());
        output.writeByte(rule.action().ordinal());
        output.writeBoolean(rule.nonBasicModifiers().isPresent());
        if (rule.nonBasicModifiers().isPresent()) {
            writeString(output, rule.nonBasicModifiers().orElseThrow());
        }
        writeString(output, rule.domains());
        writeString(output, rule.body());
        output.writeBoolean(rule.scriptletName().isPresent());
        if (rule.scriptletName().isPresent()) {
            writeString(output, rule.scriptletName().orElseThrow());
        }
    }

    private static AdblockExtendedRule readExtended(
            DataInputStream input,
            Path path
    ) throws IOException {
        AdblockExtendedRule.Syntax syntax = readEnum(
                input, AdblockExtendedRule.Syntax.values(), "extended.syntax", path);
        AdblockExtendedRule.Action action = readEnum(
                input, AdblockExtendedRule.Action.values(), "extended.action", path);
        Optional<String> modifiers = input.readBoolean()
                ? Optional.of(readString(input, path))
                : Optional.empty();
        String domains = readString(input, path);
        String body = readString(input, path);
        Optional<String> scriptletName = input.readBoolean()
                ? Optional.of(readString(input, path))
                : Optional.empty();
        return new AdblockExtendedRule(
                syntax, action, modifiers, domains, body, scriptletName);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        BinaryIO.writeString(output, value);
    }

    private static String readString(DataInputStream input, Path path) throws IOException {
        return BinaryIO.readString(input, path, "规则段字符串");
    }

    private static <T extends Enum<T>> T readEnum(
            DataInputStream input,
            T[] values,
            String field,
            Path path
    ) throws IOException {
        int ordinal = input.readUnsignedByte();
        if (ordinal >= values.length) {
            throw new IOException("规则段枚举值无效: path=" + path + ", field=" + field
                    + ", ordinal=" + ordinal);
        }
        return values[ordinal];
    }
}
