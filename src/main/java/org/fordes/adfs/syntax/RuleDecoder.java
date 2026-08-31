package org.fordes.adfs.syntax;

import org.fordes.adfs.ast.RuleAst;
import org.fordes.adfs.syntax.adblock.DialectProfile;

public interface RuleDecoder<A extends RuleAst> {

    DecodeResult<A> decode(LineSlice line, DialectProfile dialect);
}
