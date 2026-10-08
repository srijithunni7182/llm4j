package io.github.llm4j.loom.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.ScriptValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verification plan V1.1–V1.8 and V2.1–V2.6. */
class NothingIgnoredTest {

    final Scripts scripts = new Scripts();

    static final String MEMORY = """
            agent A { model: "m" system: "You are A." }
            agent B {
                model: "m"
                memory: { type: "file" path: "x.json" limit: 3 }
            }
            workflow Main() { delegate "x" to A -> x }
            """;

    static final String GUARDRAIL = """
            agent A { model: "m" }
            workflow Main() {
                guardrail (TOXICITY) { delegate "x" to A -> x }
            }
            """;

    @Test
    void v1_1_oldAgentMemoryKeysAreRejected() {
        // Agent memory is supported now (P2); the old placeholder keys point to the new ones.
        assertThatThrownBy(() -> scripts.ready(MEMORY)).isInstanceOfSatisfying(LoomLoadException.class, e -> {
            assertThat(e.problems()).allSatisfy(p -> {
                assertThat(p.line()).isEqualTo(4);
                assertThat(p.construct()).isEqualTo("agent B");
            });
            assertThat(e.getMessage()).contains("line 4: agent B: memory type: is no longer used; write conversation:");
        });
    }

    @Test
    void v1_2_unknownGuardrailType() {
        assertThatThrownBy(() -> scripts.ready(GUARDRAIL))
                .hasMessageContaining("line 3: guardrail TOXICITY: unknown guardrail type; supported: PII");
    }

    @Test
    void v1_3_unknownTool() {
        assertThatThrownBy(() -> scripts.ready("agent A { model: \"m\" tools: [Nope] }"))
                .hasMessageContaining("agent A: tool Nope is not defined")
                .hasMessageContaining(".loot").hasMessageContaining("tool Nope { use:");
    }

    @Test
    void v1_4_unknownKnowledgeRoutingAndMcpAreAllReported() {
        assertThatThrownBy(() -> scripts.ready("""
                agent A {
                    model: "m"
                    knowledge: [Missing]
                    routing: Missing
                    mcp_servers: [Missing]
                }
                """)).isInstanceOfSatisfying(LoomLoadException.class, e -> assertThat(e.problems())
                .extracting(ScriptValidator.Problem::message)
                .containsExactlyInAnyOrder(
                        "knowledge Missing is not defined (add: knowledge Missing { source: \"…\" })",
                        "routing policy Missing is not defined",
                        "mcp server Missing is not defined"));
    }

    @Test
    void v1_5_anMcpServerThatWontStartFailsTheLoad() {
        assertThatThrownBy(() -> scripts.ready("""
                mcp Files { cmd: "definitely-not-a-command-xyz" }
                agent A { model: "m" mcp_servers: [Files] }
                """)).isInstanceOf(LoomLoadException.class)
                .hasMessageContaining("line 1: mcp Files: failed to start `definitely-not-a-command-xyz`");
    }

    @Test
    void v1_6_lenientTurnsUnsupportedIntoWarnings() {
        HarnessExecutor g = scripts.executor(GUARDRAIL, x -> x.setLenient(true));
        g.initialize();
        g.executeWorkflow("Main", Map.of());
        List<ScriptValidator.Problem> problems = new ScriptValidator().validate(Scripts.parse(GUARDRAIL), g.validationContext());
        assertThat(problems).singleElement().satisfies(p -> assertThat(p.severity()).isEqualTo(ScriptValidator.Severity.WARNING));
        // Wrong memory settings are mistakes, not unsupported features: lenient doesn't hide them.
        assertThatThrownBy(() -> scripts.executor(MEMORY, x -> x.setLenient(true)).initialize()).isInstanceOf(LoomLoadException.class);
    }

    @Test
    void v1_7_lenientNeverHidesUnknownNames() {
        assertThatThrownBy(() -> scripts.executor("agent A { model: \"m\" tools: [Nope] }", x -> x.setLenient(true)).initialize())
                .isInstanceOf(LoomLoadException.class);
    }

    @Test
    void v1_8_everyProblemIsListedWithItsLine() {
        assertThatThrownBy(() -> scripts.ready("""
                agent A { model: "m" tools: [Nope] }
                agent B {
                    model: "m"
                    memory: { type: "x" }
                }
                workflow Main() { guardrail (ODD) { note "x" } }
                """)).isInstanceOfSatisfying(LoomLoadException.class, e -> {
            assertThat(e.problems()).hasSize(4); // the tool, memory's old key and its missing store, the guardrail
            assertThat(e.getMessage()).contains("4 problems").contains("line 1:").contains("line 4:").contains("line 6:");
        });
    }

    @Test
    void v2_1_skillsAreKeptWithAPersona() {
        HarnessExecutor e = scripts.ready("""
                agent A { model: "m" persona: "technicalAnalyst" skills: ["classpath://skills/coding.md"] }
                workflow Main() { delegate "x" to A -> x }
                """);
        e.executeWorkflow("Main", Map.of());
        String system = scripts.calls.get(0).system();
        assertThat(system).contains("## Skills").contains("Always use meaningful variable names");
        assertThat(system).containsIgnoringCase("analyst"); // the persona is there too
    }

    @Test
    void v2_2_skillsAreKeptWithATemplate() {
        io.github.llm4j.agent.prompt.PromptRegistry registry = new io.github.llm4j.agent.prompt.PromptRegistry() {
            @Override
            public java.util.Optional<io.github.llm4j.agent.prompt.PromptTemplate> get(String id) {
                return java.util.Optional.of(new io.github.llm4j.agent.prompt.PromptTemplate(id, "1", "TEMPLATE TEXT"));
            }

            @Override
            public java.util.Optional<io.github.llm4j.agent.prompt.PromptTemplate> get(String id, String version) {
                return get(id);
            }

            @Override
            public void reload() { }
        };
        HarnessExecutor e = scripts.executor("""
                agent A { model: "m" system_template: "t1" skills: ["classpath://skills/coding.md"] }
                workflow Main() { delegate "x" to A -> x }
                """, x -> x.setPromptRegistry(registry));
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(scripts.calls.get(0).system()).contains("TEMPLATE TEXT").contains("## Skills");
    }

    @Test
    void v2_3_aSkillThatCantLoadIsAnError() {
        assertThatThrownBy(() -> scripts.ready("agent A { model: \"m\" skills: [\"fs://no/such/skill.md\"] }"))
                .hasMessageContaining("agent A: skill fs://no/such/skill.md can't be loaded");
    }

    @Test
    void v2_4_fallbackTriesModelsInTheOrderWritten() {
        scripts.failingModels = java.util.Set.of("m1");
        HarnessExecutor e = scripts.ready("""
                routing R { strategy: "fallback" primary: "m1" fallback: ["m2", "m3"] }
                agent A { model: "m1" routing: R }
                workflow Main() { delegate "x" to A -> x }
                """);
        e.executeWorkflow("Main", Map.of());
        assertThat(scripts.calls).extracting(Scripts.Call::model).containsExactly("m1", "m2");
        assertThat(e.getContext().getVariable("x")).isEqualTo("answer from m2");
    }

    @Test
    void v2_5_strategyNamesAreNormalised() {
        assertThat(ScriptValidator.routingStrategy("COST-AWARE")).isEqualTo("cost_aware");
        assertThat(ScriptValidator.routingStrategy(" Fallback ")).isEqualTo("fallback");
        scripts.ready("""
                routing R { strategy: "COST-AWARE" primary: "m1" fallback: ["m2"] }
                agent A { model: "m1" routing: R }
                """);
    }

    @Test
    void v2_6_unknownStrategyIsAnError() {
        assertThatThrownBy(() -> scripts.ready("""
                routing R { strategy: "random" primary: "m1" }
                agent A { model: "m1" routing: R }
                """)).hasMessageContaining("line 1: routing R: unknown strategy \"random\"; use cost_aware or fallback");
        assertThatThrownBy(() -> scripts.ready("routing R { strategy: \"fallback\" }"))
                .hasMessageContaining("routing R: needs primary");
    }
}
