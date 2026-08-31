package org.fordes.adfs.engine;

import org.fordes.adfs.config.BuildPlan;
import org.fordes.adfs.model.AdblockExtendedRule;
import org.fordes.adfs.model.CanonicalRule;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

final class RuleSegment {

    private static final int MAGIC = 0x41444653;
    private static final int VERSION = 2;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;
    private static final int RECORD = 1;
    private static final int END = 0;

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
                    || record.sourceFormat() != source.format()
                    || record.sourceDialect() != source.dialect()
                    || record.sourceClashDialect() != source.clashDialect()) {
                throw new IOException("规则与规则段来源元数据不一致: source=" + source.id());
            }
            output.writeByte(RECORD);
            output.writeByte(record.sourceSyntax().ordinal());
            writeString(output, record.raw());
            output.writeBoolean(record.canonical().isPresent());
            if (record.canonical().isPresent()) {
                CanonicalRule canonical = record.canonical().orElseThrow();
                output.writeByte(canonical.matchType().ordinal());
                output.writeByte(canonical.action().ordinal());
                output.writeLong(canonical.featureMask());
                writeString(output, canonical.value());
                output.writeBoolean(canonical.destination().isPresent());
                if (canonical.destination().isPresent()) {
                    writeString(output, canonical.destination().orElseThrow());
                }
            }
            output.writeBoolean(record.extended().isPresent());
            if (record.extended().isPresent()) {
                writeExtended(output, record.extended().orElseThrow());
            }
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

            RuleRecord.SourceSyntax syntax = readEnum(
                    input, RuleRecord.SourceSyntax.values(), "sourceSyntax", path);
            String raw = readString(input, path);
            Optional<CanonicalRule> canonical = input.readBoolean()
                    ? Optional.of(readCanonical(input, path))
                    : Optional.empty();
            Optional<AdblockExtendedRule> extended = input.readBoolean()
                    ? Optional.of(readExtended(input, path))
                    : Optional.empty();
            return new RuleRecord(
                    sourceId,
                    format,
                    dialect,
                    clashDialect,
                    raw,
                    canonical,
                    extended,
                    syntax
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
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, Path path) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("规则段字符串长度无效: path=" + path + ", length=" + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("规则段字符串被截断: path=" + path + ", expected=" + length
                    + ", actual=" + bytes.length);
        }
        return new String(bytes, StandardCharsets.UTF_8);
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
