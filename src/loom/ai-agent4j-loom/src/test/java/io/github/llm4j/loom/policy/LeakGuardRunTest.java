package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeakGuardRunTest {

    @TempDir Path dir;

    static final String SYSTEM = "You are a careful support assistant for Acme and you must always greet the customer by name before anything else";

    String script(String guard, boolean policy) {
        return (policy ? "evaluator E { model: \"j\" }\npolicy P: \"house-rules\" checked by E\n" : "")
                + "agent A { model: \"m\" system: \"" + SYSTEM + "\" " + guard + " }\n"
                + "workflow Main(q) { delegate \"{q}\" to A -> r\n  note \"after\" }\n";
    }

    record Out(Throwable error, List<TraceEvent> events) {}

    Out run(String source, String answer) throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies/house-rules.md"), "## r_one\nBreaking it.\n");
        io.github.llm4j.LLMClient client =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public LLMResponse chat(io.github.llm4j.model.LLMRequest request) {
                        return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer + "\"}\n```").model("t").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(io.github.llm4j.model.LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        LoomScript parsed = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor ex = new HarnessExecutor(parsed, new ToolRegistry(), m -> client);
        ex.setBaseDir(dir);
        ex.setStaticSinkChecks(false);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        ex.addTraceListener(events::add);
        ex.initialize();
        Throwable error = null;
        try {
            ex.executeWorkflow("Main", Map.of("q", "hi"));
        } catch (Throwable t) {
            error = t;
        }
        return new Out(error, events);
    }

    static boolean leaked(Out o) {
        return o.events().stream().anyMatch(e -> e.type().equals(TraceEvent.GUARD) && String.valueOf(e.data()).contains("repeated_instructions"));
    }

    @Test
    void underAPolicyEveryAgentWarnsByDefaultAndTheRunCarriesOn() throws Exception {
        Out o = run(script("", true), SYSTEM);
        assertThat(o.error()).isNull();
        assertThat(leaked(o)).isTrue();
    }

    @Test
    void withoutAPolicyTheGuardIsOffUnlessAskedFor() throws Exception {
        assertThat(leaked(run(script("", false), SYSTEM))).isFalse();
        assertThat(leaked(run(script("guard { prompt_leak: warn }", false), SYSTEM))).isTrue();
    }

    @Test
    void blockFailsTheStepAndNamesTheKindNotTheText() throws Exception {
        Out o = run(script("guard { prompt_leak: block }", true), SYSTEM);
        assertThat(o.error()).isNotNull();
        assertThat(o.error().getMessage()).contains("repeated_instructions").doesNotContain("careful support");
    }

    @Test
    void offIsAllowed() throws Exception {
        assertThat(leaked(run(script("guard { prompt_leak: off }", true), SYSTEM))).isFalse();
    }

    @Test
    void anInternalReferenceIsCaughtEvenWithoutAPolicy() throws Exception {
        Out o = run(script("guard { internal_refs: [\"Section [0-9]+\"] prompt_leak: block }", false), "Per Section 4 we help");
        assertThat(o.error()).isNotNull();
    }

    @Test
    void theEventNamesTheKindAndNeverTheText() throws Exception {
        Out o = run(script("", true), SYSTEM);
        var e = o.events().stream().filter(t -> t.type().equals(TraceEvent.GUARD)).findFirst().orElseThrow();
        assertThat(String.valueOf(e.data()) + e.text()).doesNotContain("careful support");
    }

    @Test
    void badSettingsAreCheckErrors() throws Exception {
        for (String guard : new String[] {"guard { prompt_leak: maybe }", "guard { prompt_leak_words: 0 prompt_leak: warn }", "guard { internal_refs: [\"(\"] }"}) {
            var s = new LoomParser(new Lexer(script(guard, false)).tokenize()).parseScript();
            HarnessExecutor ex = new HarnessExecutor(s, new ToolRegistry(), m -> null);
            ex.setBaseDir(dir);
            var problems = new ScriptValidator().validate(s, ex.validationContext());
            assertThat(problems).as(guard).anyMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && (p.message().contains("guard") || p.message().contains("internal_refs")));
        }
    }
}
