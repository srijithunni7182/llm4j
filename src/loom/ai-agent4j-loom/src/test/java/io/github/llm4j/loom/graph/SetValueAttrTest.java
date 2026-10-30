package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static io.github.llm4j.loom.graph.GraphTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Phase 21 R4: a {@code set} node says what it sets, so the editor can list a group of them. */
class SetValueAttrTest {

    private static GraphNode set(String line) {
        return node(graphOf("workflow W() {\n  " + line + "\n}\n", "W"), "n1");
    }

    @Test
    void aTextNumberBooleanAndReferenceAreShownAsWritten() {
        assertThat(set("set a = \"hello\"").attrs()).containsEntry("value", "\"hello\"");
        assertThat(set("set n = 3").attrs()).containsEntry("value", "3");
        assertThat(set("set ok = true").attrs()).containsEntry("value", "true");
        assertThat(set("set r = request.amount").attrs()).containsEntry("value", "request.amount");
    }

    @Test
    void anObjectShowsItsFieldNamesNotTheirValues() {
        assertThat(set("set o = { a: 1, b: \"x\" }").attrs()).containsEntry("value", "{ a, b }");
    }

    @Test
    void aLongValueIsCutAndTheLabelStaysTheSame() {
        GraphNode n = set("set big = \"" + "x".repeat(400) + "\"");

        assertThat(n.label()).isEqualTo("set big");
        assertThat(String.valueOf(n.attrs().get("value"))).hasSizeLessThanOrEqualTo(200).endsWith("…");
    }

    @Test
    void aSecretShapedValueIsMaskedLikeEveryOtherTextOnANode() {
        GraphNode n = set("set k = \"sk-abcdefghijklmnopqrstuvwxyz0123456789ABCDEF\"");

        assertThat(String.valueOf(n.attrs().get("value"))).doesNotContain("abcdefghijklmnopqrstuvwxyz0123456789");
    }
}
