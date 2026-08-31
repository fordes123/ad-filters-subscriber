package org.fordes.adfs.cli;

import org.fordes.adfs.ast.CommentAst;
import org.fordes.adfs.ast.CosmeticRuleAst;
import org.fordes.adfs.ast.EmptyAst;
import org.fordes.adfs.ast.ExtendedAction;
import org.fordes.adfs.ast.ExtensionAst;
import org.fordes.adfs.ast.HtmlFilterAst;
import org.fordes.adfs.ast.MetadataAst;
import org.fordes.adfs.ast.NetworkModifierAst;
import org.fordes.adfs.ast.NetworkRuleAst;
import org.fordes.adfs.ast.OpaqueAst;
import org.fordes.adfs.ast.PreprocessorDirectiveAst;
import org.fordes.adfs.ast.RuleAst;
import org.fordes.adfs.ast.ScriptletRuleAst;
import org.fordes.adfs.syntax.DecodeResult;
import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.RuleDecoder;
import org.fordes.adfs.syntax.Span;
import org.fordes.adfs.syntax.adblock.AdblockDecoder;
import org.fordes.adfs.syntax.adblock.DialectProfile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "inspect",
        mixinStandardHelpOptions = true,
        description = "解析并显示单条 Adblock 规则的无损 AST"
)
public final class InspectCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<rule>", description = "待解析规则")
    private String rule;

    @Option(names = "--dialect", defaultValue = "ABP", description = "方言：${COMPLETION-CANDIDATES}")
    private DialectProfile dialect;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        LineSlice line = LineSlice.fromUtf8(rule);
        RuleDecoder<RuleAst> decoder = new AdblockDecoder();
        DecodeResult<RuleAst> result = decoder.decode(line, dialect);

        if (result instanceof DecodeResult.Invalid<RuleAst> invalid) {
            printInvalid(invalid);
            return 2;
        }

        DecodeResult.Decoded<RuleAst> decoded = (DecodeResult.Decoded<RuleAst>) result;
        printDecoded(decoded.ast());
        return 0;
    }

    private void printDecoded(RuleAst ast) {
        switch (ast) {
            case NetworkRuleAst network -> printNetwork(network);
            case CosmeticRuleAst cosmetic -> printExtended(
                    "COSMETIC", cosmetic.source(), cosmetic.dialect(), cosmetic.action(),
                    cosmetic.syntax().name, cosmetic.nonBasicModifiers(), cosmetic.domains(),
                    cosmetic.separator(), cosmetic.body());
            case ScriptletRuleAst scriptlet -> printExtended(
                    "SCRIPTLET", scriptlet.source(), scriptlet.dialect(), scriptlet.action(),
                    scriptlet.syntax().name, scriptlet.nonBasicModifiers(), scriptlet.domains(),
                    scriptlet.separator(), scriptlet.body());
            case HtmlFilterAst html -> printExtended(
                    "HTML_FILTER", html.source(), html.dialect(), html.action(),
                    html.syntax().name, html.nonBasicModifiers(), html.domains(),
                    html.separator(), html.body());
            case ExtensionAst extension -> printExtended(
                    "EXTENSION", extension.source(), extension.dialect(), extension.action(),
                    extension.kind().name, extension.nonBasicModifiers(), extension.domains(),
                    extension.separator(), extension.body());
            case CommentAst comment -> printSimple(
                    "COMMENT", comment.source(), comment.dialect(), "body", comment.body());
            case MetadataAst metadata -> printSimple(
                    "METADATA", metadata.source(), metadata.dialect(), "body", metadata.body());
            case PreprocessorDirectiveAst directive -> printDirective(directive);
            case EmptyAst empty -> printEmpty(empty);
            case OpaqueAst opaque -> printOpaque(opaque);
        }
    }

    private void printNetwork(NetworkRuleAst ast) {
        PrintWriter out = spec.commandLine().getOut();
        printHeader(out, "NETWORK", ast.dialect().name);
        out.println("action=" + ast.action().name);
        out.println("left-anchor=" + ast.leftAnchor().name);
        out.println("right-anchor=" + ast.rightAnchor());
        out.println("regex=" + ast.regex());
        out.println("pattern=" + ast.source().materialize(ast.pattern()));
        out.println("modifier-count=" + ast.modifiers().size());
        for (NetworkModifierAst modifier : ast.modifiers()) {
            String name = ast.source().materialize(modifier.name());
            String prefix = modifier.negated() ? "~" : "";
            String value = modifier.value()
                    .map(ast.source()::materialize)
                    .map(item -> "=" + item)
                    .orElse("");
            out.println("modifier=" + prefix + name + value);
        }
        out.println("raw=" + ast.source().materialize());
    }

    private void printExtended(
            String kind,
            LineSlice source,
            DialectProfile dialect,
            ExtendedAction action,
            String syntax,
            Optional<Span> nonBasicModifiers,
            Span domains,
            Span separator,
            Span body
    ) {
        PrintWriter out = spec.commandLine().getOut();
        printHeader(out, kind, dialect.name);
        out.println("action=" + action.name);
        out.println("syntax=" + syntax);
        nonBasicModifiers.ifPresent(span -> out.println(
                "non-basic-modifiers=" + source.materialize(span)));
        out.println("domains=" + source.materialize(domains));
        out.println("separator=" + source.materialize(separator));
        out.println("body=" + source.materialize(body));
        out.println("raw=" + source.materialize());
    }

    private void printSimple(
            String kind,
            LineSlice source,
            DialectProfile dialect,
            String field,
            Span span
    ) {
        PrintWriter out = spec.commandLine().getOut();
        printHeader(out, kind, dialect.name);
        out.println(field + "=" + source.materialize(span));
        out.println("raw=" + source.materialize());
    }

    private void printDirective(PreprocessorDirectiveAst ast) {
        PrintWriter out = spec.commandLine().getOut();
        printHeader(out, "PREPROCESSOR", ast.dialect().name);
        out.println("name=" + ast.source().materialize(ast.name()));
        ast.value().ifPresent(span -> out.println("value=" + ast.source().materialize(span)));
        out.println("raw=" + ast.source().materialize());
    }

    private void printEmpty(EmptyAst ast) {
        PrintWriter out = spec.commandLine().getOut();
        printHeader(out, "EMPTY", ast.dialect().name);
        out.println("raw=" + ast.source().materialize());
    }

    private void printOpaque(OpaqueAst ast) {
        PrintWriter out = spec.commandLine().getOut();
        out.println("status=opaque");
        out.println("kind=" + ast.kind().name);
        out.println("dialect=" + ast.dialect().name);
        out.println("raw=" + ast.source().materialize());
    }

    private void printInvalid(DecodeResult.Invalid<RuleAst> invalid) {
        PrintWriter out = spec.commandLine().getOut();
        out.println("status=invalid");
        out.println("code=" + invalid.diagnostic().code());
        out.println("offset=" + invalid.diagnostic().offset());
        out.println("reason=" + invalid.diagnostic().message());
        out.println("raw=" + invalid.source().materialize());
    }

    private static void printHeader(PrintWriter out, String kind, String dialect) {
        out.println("status=decoded");
        out.println("kind=" + kind);
        out.println("dialect=" + dialect);
    }
}
