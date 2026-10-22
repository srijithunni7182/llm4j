package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

class SteerableAgentTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    @Test
    void anAgentCanBeDeclaredStreamingReasoningAndSteerable() {
        AgentDef a =
                parse("agent A { model: \"m\" streaming: true reasoning: true steerable: true }")
                        .getAgents()
                        .get(0);
        assertTrue(a.isStreaming());
        assertTrue(a.isReasoning());
        assertTrue(a.isSteerable());
    }

    @Test
    void theseAreOffUnlessDeclared() {
        AgentDef a = parse("agent A { model: \"m\" }").getAgents().get(0);
        assertFalse(a.isStreaming());
        assertFalse(a.isReasoning());
        assertFalse(a.isSteerable());
        assertFalse(
                parse("agent A { model: \"m\" steerable: false }")
                        .getAgents()
                        .get(0)
                        .isSteerable());
    }

    @Test
    void aValueThatIsNotTrueOrFalseIsRefused() {
        RuntimeException e =
                assertThrows(
                        RuntimeException.class,
                        () -> parse("agent A { model: \"m\" steerable: yes }"));
        assertTrue(e.getMessage().contains("steerable must be true or false"), e.getMessage());
    }

    @Test
    void theUnknownSettingHintListsTheNewOnes() {
        RuntimeException e =
                assertThrows(
                        RuntimeException.class,
                        () -> parse("agent A { model: \"m\" steerabel: true }"));
        assertTrue(e.getMessage().contains("steerable"), e.getMessage());
    }

    private static java.util.List<String> warnings(String source) {
        return new ScriptValidator()
                .validate(parse(source), new ScriptValidator.Context()).stream()
                        .filter(p -> p.severity() == ScriptValidator.Severity.WARNING)
                        .map(ScriptValidator.Problem::message)
                        .toList();
    }

    @Test
    void steerableWithoutStreamingIsWarnedAbout() {
        assertTrue(
                warnings("agent A { model: \"m\" steerable: true }").stream()
                        .anyMatch(m -> m.contains("without streaming")));
        assertTrue(warnings("agent A { model: \"m\" steerable: true streaming: true }").isEmpty());
        assertTrue(
                warnings("agent A { model: \"m\" reasoning: true }").stream()
                        .anyMatch(m -> m.contains("no effect without streaming")));
    }
}
