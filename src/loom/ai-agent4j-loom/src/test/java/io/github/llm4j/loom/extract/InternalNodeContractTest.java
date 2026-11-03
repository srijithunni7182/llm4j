package io.github.llm4j.loom.extract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.eval.testing.ScriptedClassifier;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Phase 24, R7 (program page section 4.1): one contract for every internal node. The same canary
 * workflow is run with each node kind; none may see the transcript or the shared prompt, none may
 * write its answer to the transcript, and each sees only the inputs its statement names. Phase 26
 * adds the evaluator to this test.
 */
class InternalNodeContractTest {

    enum Kind {
        CLASSIFIER(
                "intentclassifier Node { model: \"m\" choose kind: a | b }",
                "intentclassify Node on {INPUT} -> out"),
        EXTRACTOR(
                "extractor Node { model: \"m\" fields { kind: string } }",
                "extract Node from {INPUT} -> out");

        final String declaration;
        final String statement;

        Kind(String declaration, String statement) {
            this.declaration = declaration;
            this.statement = statement;
        }
    }

    static final String SHARED = "SHARED-CANARY-77";
    static final String EARLIER = "ANSWER-CANARY-42";
    static final String NAMED = "NAMED-INPUT-9";

    /** What each node was sent, as one string, and what the agent after it was sent. */
    record Seen(String node, String agentAfter) {}

    private static Seen run(Kind kind) {
        String script =
                "shared_prompt: \"" + SHARED + "\"\n"
                        + "agent Chatty { model: \"test\" system: \"You are Chatty.\" }\n"
                        + kind.declaration + "\n"
                        + "workflow Main(text) {\n"
                        + "  delegate \"first\" to Chatty -> said\n"
                        + "  " + kind.statement.replace("{INPUT}", "text") + "\n"
                        + "  delegate \"after\" to Chatty -> later\n"
                        + "}\n";
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        List<LLMRequest> chat = Collections.synchronizedList(new ArrayList<>());
        CapturingClient client =
                new CapturingClient("{\"kind\": \"a\"}") {
                    @Override
                    public io.github.llm4j.model.LLMResponse chat(LLMRequest request) {
                        chat.add(request);
                        if (CapturingClient.text(request).contains("You are Chatty"))
                            return io.github.llm4j.model.LLMResponse.builder()
                                    .content(EARLIER)
                                    .model("test")
                                    .build();
                        return super.chat(request);
                    }
                };
        ScriptedClassifier classifier =
                ScriptedClassifier.builder().choice("kind", "a", 0.99).build();
        HarnessExecutor executor =
                new HarnessExecutor(parsed, new ToolRegistry(), model -> client);
        executor.setClassifierFactory((model, samples) -> classifier);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of("text", NAMED));

        String node;
        if (kind == Kind.CLASSIFIER) {
            ClassifyRequest sent = classifier.requests().get(0);
            node = sent.state().toString();
        } else {
            node =
                    chat.stream()
                            .filter(r -> !CapturingClient.text(r).contains("You are Chatty"))
                            .map(CapturingClient::text)
                            .findFirst()
                            .orElse("");
        }
        String after = CapturingClient.text(chat.get(chat.size() - 1));
        return new Seen(node, after);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aNodeNeverSeesTheTranscriptOrTheSharedPrompt(Kind kind) {
        Seen seen = run(kind);

        assertThat(seen.node())
                .doesNotContain("Conversation History")
                .doesNotContain(EARLIER)
                .doesNotContain(SHARED)
                .doesNotContain("You are Chatty");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aNodeSeesTheInputsItsStatementNames(Kind kind) {
        assertThat(run(kind).node()).contains(NAMED);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aNodesAnswerIsNotWrittenToTheTranscript(Kind kind) {
        Seen seen = run(kind);

        assertThat(seen.agentAfter()).contains(EARLIER); // the transcript holds what agents said
        assertThat(seen.agentAfter()).doesNotContain(NAMED);
    }

    @Test
    void everyNodeKindIsCoveredHere() {
        // a fourth internal node kind must be added to Kind, or this fails
        assertThat(Kind.values()).extracting(Enum::name).containsExactly("CLASSIFIER", "EXTRACTOR");
    }
}
