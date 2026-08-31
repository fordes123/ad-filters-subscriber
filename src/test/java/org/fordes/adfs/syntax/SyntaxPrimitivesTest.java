package org.fordes.adfs.syntax;

import org.fordes.adfs.syntax.classifier.RuleClassification;
import org.fordes.adfs.syntax.classifier.RuleKind;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SyntaxPrimitivesTest {

    @Test
    void slicesUtf8ByByteOffsetsAndMaterializesSpans() {
        byte[] bytes = "x广告规则y".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        LineSlice line = new LineSlice(bytes, 1, bytes.length - 1);

        assertEquals("广告规则", line.materialize());
        assertEquals("广告", line.materialize(new Span(0, 6)));
        assertThrows(IndexOutOfBoundsException.class, () -> line.byteAt(line.length()));
        assertThrows(IndexOutOfBoundsException.class,
                () -> line.materialize(new Span(0, line.length() + 1)));
    }

    @Test
    void validatesSpanAndClassificationInvariants() {
        Span span = new Span(2, 2);
        assertEquals(0, span.length());
        assertTrue(span.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Span(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Span(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new RuleClassification(
                RuleKind.NETWORK,
                0,
                Optional.of(org.fordes.adfs.syntax.classifier.SeparatorKind.ELEMENT_HIDING),
                Optional.empty()
        ));
    }
}
