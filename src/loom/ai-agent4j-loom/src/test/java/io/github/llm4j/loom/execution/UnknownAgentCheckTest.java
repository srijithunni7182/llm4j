package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A step that names an agent the script does not define is an error before the run, not "Agent not found" in the middle of one. */
class UnknownAgentCheckTest {

    private static List<ScriptValidator.Problem> check(String body) {
        var script = new LoomParser(new Lexer("agent Writer { model: \"ollama/llama3\" system: \"s\" }\nagent Editor { model: \"ollama/llama3\" system: \"s\" }\n" + body).tokenize()).parseScript();
        var executor = new HarnessExecutor(script, new ToolRegistry(), new io.github.llm4j.loom.eval.MockModels());
        return new ScriptValidator().validate(script, executor.validationContext());
    }

    @Test
    void aDelegateToAnAgentThatIsNotDefinedIsAnErrorWithTheLineTheNearestNameAndTheKnownOnes() {
        var problems = check("workflow Main() {\n    delegate \"x\" to Writter -> out_text\n    note \"{out_text}\"\n}\n");

        assertThat(problems).filteredOn(p -> p.severity() == ScriptValidator.Severity.ERROR).singleElement().satisfies(p -> {
            assertThat(p.line()).isEqualTo(4);
            assertThat(p.message()).contains("there is no agent named Writter").contains("did you mean Writer?").contains("Agents defined: Writer, Editor.");
        });
    }

    @Test
    void aBroadcastIsCheckedTooButAHandoffMayEndAPathWithAnyName() {
        var problems = check("workflow Main() {\n    broadcast \"x\" to [Writer, Ghost] -> all_text\n    handoff \"y\" to Terminal\n}\n");

        assertThat(problems).extracting(ScriptValidator.Problem::message).anyMatch(m -> m.contains("no agent named Ghost")).noneMatch(m -> m.contains("Terminal"));
    }

    @Test
    void definedAgentsAndAgentsChosenByTheDataAreFine() {
        var problems = check("workflow Main() {\n    delegate \"x\" to Writer -> a_text\n    delegate \"{a_text}\" to Editor -> b_text\n    note \"{b_text}\"\n}\n");

        assertThat(problems).filteredOn(p -> p.severity() == ScriptValidator.Severity.ERROR).isEmpty();
    }
}
