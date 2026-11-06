package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R22: a step that sends files says so on its node, by kind, so a
 * reader of the graph sees where pictures, recordings and documents go. The file names are not
 * shown: they can be script text with variables in it.
 */
class AttachGraphTest {

    private static GraphNode node(String kind, String step) {
        var script =
                new LoomParser(
                                new Lexer(
                                                "media_root: \"assets\"\n"
                                                        + "agent Reader { model: \"gemini-2.5-flash\" system: \"x\" }\n"
                                                        + "extractor Form { model: \"gemini-2.5-flash\" fields { total: number } }\n"
                                                        + "workflow Main(receipt) {\n"
                                                        + step
                                                        + "\n}\n")
                                        .tokenize())
                        .parseScript();
        WorkflowGraph g = new GraphBuilder().build(script.getWorkflows().get(0), "test.loom");
        return g.nodes().stream().filter(n -> kind.equals(n.kind())).findFirst().orElseThrow();
    }

    @Test
    void aDelegateListsTheKindsItAttachesInOrder() {
        GraphNode n =
                node(
                        Kinds.DELEGATE,
                        "delegate \"x\" to Reader attach image \"{receipt}\", document \"policy.pdf\" -> out");

        assertThat(((List<?>) n.attrs().get("attaches")).toArray()).containsExactly("image", "document");
    }

    @Test
    void theFileNamesAreNotShown() {
        GraphNode n = node(Kinds.DELEGATE, "delegate \"x\" to Reader attach image \"secret-name.png\" -> out");

        assertThat(n.attrs().toString()).doesNotContain("secret-name");
    }

    @Test
    void broadcastHandoffAndExtractSayTheSame() {
        assertThat(
                                ((List<?>)
                                node(Kinds.BROADCAST, "broadcast \"x\" to [Reader] attach audio \"a.wav\" -> out")
                                        .attrs()
                                        .get("attaches")).toArray())
                .containsExactly("audio");
        assertThat(
                                ((List<?>)
                                node(Kinds.HANDOFF, "handoff \"x\" to Reader attach video \"c.mp4\"")
                                        .attrs()
                                        .get("attaches")).toArray())
                .containsExactly("video");
        assertThat(
                                ((List<?>)
                                node(Kinds.EXTRACT, "extract Form from (a = image \"a.png\", b = document \"b.pdf\") -> f")
                                        .attrs()
                                        .get("attaches")).toArray())
                .containsExactly("image", "document");
    }

    @Test
    void aQuestionToAPersonSaysSoToo() {
        assertThat(
                        ((List<?>)
                                        node(Kinds.HUMAN_PROMPT, "human_prompt \"ok?\" attach image \"a.png\" -> a")
                                                .attrs()
                                                .get("attaches"))
                                .toArray())
                .containsExactly("image");
    }

    @Test
    void aStepWithNoAttachmentsHasNoSuchAttribute() {
        assertThat(node(Kinds.DELEGATE, "delegate \"x\" to Reader -> out").attrs()).doesNotContainKey("attaches");
    }
}
