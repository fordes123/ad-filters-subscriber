package org.fordes.adfs.ast;

import org.fordes.adfs.syntax.LineSlice;
import org.fordes.adfs.syntax.adblock.DialectProfile;

public sealed interface RuleAst permits CommentAst, CosmeticRuleAst, EmptyAst, ExtensionAst,
        HtmlFilterAst, MetadataAst, NetworkRuleAst, PreprocessorDirectiveAst,
        ScriptletRuleAst {

    LineSlice source();

    DialectProfile dialect();
}
