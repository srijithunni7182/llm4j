package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ObserveStmt;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * One rule for filling a text: {@code {name}} and {@code {name.field}} are replaced by a
 * variable's value, and a plain word is always a word, even when it is a variable's name. A variable
 * written alone where a text goes ({@code delegate draft to …}, {@code call W(x = topic)}) means its
 * value. There is no setting, and the old one is a load error that says to delete it.
 */
class PlaceholderRuleTest {

    private static final String BODY =
            "agent A { model: \"gemini-2.5-flash\" system: \"You help.\" }\n"
                    + "workflow W(out) {\n"
                    + "    delegate \"length-out-of-range: {out} and out\" to A -> r\n"
                    + "    return r\n"
                    + "}\n";

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static List<String> sent(String source, String workflow) {
        List<String> prompts = new CopyOnWriteArrayList<>();
        LLMClientFactory factory =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                prompts.add(request.getMessages().get(request.getMessages().size() - 1).getContent());
                                return LLMResponse.builder().content("ok").model(model).build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.empty();
                            }
                        };
        HarnessExecutor executor = new HarnessExecutor(parse(source), new ToolRegistry(), factory);
        executor.initialize();
        executor.executeWorkflow(workflow, Map.of("out", "7"));
        return prompts;
    }

    @Test
    void onlyTheBracedNameIsReplacedAndAWordStaysAWord() {
        assertThat(sent(BODY, "W").get(0)).contains("length-out-of-range: 7 and out");
    }

    @Test
    void theOldSettingIsALoadErrorThatSaysToDeleteIt() {
        for (String mode : List.of("braces", "legacy", "curly"))
            assertThatThrownBy(() -> parse("substitution: " + mode + "\n" + BODY))
                    .hasMessageContaining("substitution: is no longer a setting")
                    .hasMessageContaining("Delete this line");
    }

    @Test
    void theWordSubstitutionStillWorksAsAName() {
        LoomScript script =
                parse("agent substitution { model: \"gemini-2.5-flash\" }\nworkflow W(substitution) {\n delegate \"x\" to substitution -> r\n}\n");
        assertThat(script.getWorkflows()).hasSize(1);
    }

    @Test
    void aVariableWrittenAloneWhereATextGoesMeansItsValue() {
        LoomScript s =
                parse(
                        "agent A { model: \"m\" system: \"s\" }\n"
                                + "workflow Helper(topic) { return topic }\n"
                                + "workflow W(out) {\n"
                                + "    delegate out to A -> r\n"
                                + "    call Helper(topic = out) -> h\n"
                                + "    observe \"seen\" {out + \" and \" + r.field}\n"
                                + "    handoff r to A\n"
                                + "}\n");
        List<io.github.llm4j.loom.ast.Statement> body = s.getWorkflows().get(1).getStatements();
        assertThat(((DelegateStmt) body.get(0)).getPayload()).isEqualTo("{out}");
        assertThat(((CallStmt) body.get(1)).getArguments()).containsEntry("topic", "{out}");
        assertThat(((ObserveStmt) body.get(2)).getExpression()).isEqualTo("{out} and {r.field}");
        assertThat(((HandoffStmt) body.get(3)).getPayload()).isEqualTo("{r}");
        assertThat(
                        ((ObserveStmt)
                                        parse("workflow W(a, b) {\n observe \"x\" {a, b.kind}\n}\n")
                                                .getWorkflows()
                                                .get(0)
                                                .getStatements()
                                                .get(0))
                                .getExpression())
                .as("a list of values, as a model writes it")
                .isEqualTo("{a}, {b.kind}");
        assertThatThrownBy(() -> parse("workflow W(a) { observe \"x\" {a 1} }\n"))
                .hasMessageContaining("joined by +");
    }

    @Test
    void aCalledWorkflowGetsTheValueOfAVariablePassedByName() {
        String source =
                "agent A { model: \"gemini-2.5-flash\" system: \"s\" }\n"
                        + "workflow Helper(topic) { delegate \"about {topic}\" to A -> r\n return r }\n"
                        + "workflow W(out) { call Helper(topic = out) -> h\n return h }\n";
        assertThat(sent(source, "W").get(0)).contains("about 7").doesNotContain("about topic");
    }
}
