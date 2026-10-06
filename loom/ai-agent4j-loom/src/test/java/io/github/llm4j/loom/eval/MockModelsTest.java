package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R3.5 of loom-weave-eval: a model that costs nothing and still gives every step something well formed to read. */
class MockModelsTest {

    private final MockModels mock = new MockModels();

    private String ask(String user) {
        return mock.createClient("any/model").chat(LLMRequest.builder().messages(List.of(Message.system("s"), Message.user(user))).build()).getContent();
    }

    @Test
    void withoutASchemaTheAnswerIsAFixedLine() {
        assertThat(ask("What is the weather?")).contains("\"final_answer\": \"" + MockModels.ANSWER + "\"");
    }

    @Test
    void aSchemaGetsAValueOfThatSchema() {
        String reply = ask("Review this.\n\nCRITICAL: You MUST respond in valid JSON format only, following this schema: "
                + "{verdict: enum[OK, REWRITE], advice: string, score: number, done: boolean, tags: list<string>, inner: {a: string}}");

        assertThat(reply).contains("final_answer")
                .contains("{\\\"verdict\\\": \\\"OK\\\", \\\"advice\\\": \\\"mock\\\", \\\"score\\\": 0, \\\"done\\\": false, \\\"tags\\\": [], \\\"inner\\\": {\\\"a\\\": \\\"mock\\\"}}");
    }

    @Test
    void theFlatTypesAreSampled() {
        assertThat(ask("following this schema: string")).contains("\\\"mock\\\"");
        assertThat(ask("following this schema: number")).contains("final_answer\": \"0\"");
        assertThat(ask("following this schema: boolean")).contains("final_answer\": \"false\"");
        assertThat(ask("following this schema: enum[A, B]")).contains("\\\"A\\\"");
    }

    @Test
    void aModelOfAnyNameIsAcceptedAndNothingNeedsAKey() {
        assertThat(mock.problem("gemini-2.5-flash")).isNull();
        assertThat(mock.createClient("whatever")).isNotNull();
    }

    @Test
    void theHarnessAcceptsTheRepliesForItsOwnSchemaText() {
        // the schema text is written by the harness itself; this proves the mock reads that, not a copy of it
        String script = """
                agent A { model: "m" system: "s" }
                workflow Main() {
                    delegate "review" to A -> review expecting { verdict: enum["OK", "REWRITE"], advice: string, n: number }
                    delegate "again" to A -> items expecting list<string>
                }
                """;
        HarnessExecutor e = EvalTestSupport.executor(script, mock, new ToolRegistry());

        e.executeWorkflow("Main", Map.of());

        assertThat(String.valueOf(e.getContext().getVariable("review"))).contains("OK").contains("mock");
        assertThat(e.getContext().getVariable("items")).isNotNull();
    }
}
