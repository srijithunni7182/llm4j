package io.github.llm4j.loom.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.Finding;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 27e, R11: routing a model chooses stays inside the script's limits. */
class RoutingLimitsTest {

    static LoomScript parse(String s) {
        return new LoomParser(new Lexer(s).tokenize()).parseScript();
    }

    static final String AGENTS =
            "substitution: braces\n"
                    + "agent Researcher { model: \"m\" system: \"You are the researcher.\" }\n"
                    + "agent Writer { model: \"m\" system: \"You are the writer.\" }\n"
                    + "agent Editor { model: \"m\" system: \"You are the editor.\" }\n";

    // ---- syntax

    @Test
    void anAllowListIsParsedAndOnlyForARunTimeTarget() {
        LoomScript s = parse(AGENTS + "workflow Main(task) { delegate \"x\" to {task.owner} in [Researcher, Writer] -> r }\n");
        DelegateStmt d = (DelegateStmt) s.getWorkflows().get(0).getStatements().get(0);
        assertThat(d.getAllowedTargets()).containsExactly("Researcher", "Writer");
        assertThatThrownBy(() -> parse(AGENTS + "workflow Main(t) { delegate \"x\" to Writer in [Writer] -> r }\n"))
                .hasMessageContaining("is for a target chosen at run time");
    }

    @Test
    void theMapFormParses() {
        LoomScript s = parse(AGENTS + "workflow Main(task) { delegate \"x\" to Writer -> results[task.name] }\n");
        DelegateStmt d = (DelegateStmt) s.getWorkflows().get(0).getStatements().get(0);
        assertThat(d.getVariableName()).isEqualTo("results[task.name]");
        assertThat(d.hasDynamicVariable()).isTrue();
    }

    // ---- checks

    List<ScriptValidator.Problem> check(String source) {
        LoomScript s = parse(source);
        HarnessExecutor ex = new HarnessExecutor(s, new ToolRegistry(), m -> null);
        return new ScriptValidator().validate(s, ex.validationContext());
    }

    @Test
    void aNameInTheListThatIsNotAnAgentIsAnError() {
        var p = check(AGENTS + "workflow Main(task) { delegate \"x\" to {task.owner} in [Writer, Ghost] -> r }\n");
        assertThat(p).anyMatch(x -> x.severity() == ScriptValidator.Severity.ERROR && x.message().contains("Ghost"));
    }

    @Test
    void anUnboundedTargetIsAWarningWhenNoAgentChangesThingsOrReachesPrivateData() {
        var p = check(AGENTS + "workflow Main(task) { delegate \"x\" to {task.owner} -> r }\n");
        assertThat(p).anyMatch(x -> x.severity() == ScriptValidator.Severity.WARNING && x.message().contains("no list of agents"));
        assertThat(p).noneMatch(x -> x.severity() == ScriptValidator.Severity.ERROR);
    }

    @Test
    void anUnboundedTargetIsAnErrorWhenSomeAgentCouldChangeThings() {
        String src = AGENTS + "tool Pay { use: webhook url: \"https://example.com/h\" effect: changes }\n"
                + "agent Payer { model: \"m\" system: \"pay\" tools: [Pay] approve: [Pay] }\n"
                + "workflow Main(task) { delegate \"x\" to {task.owner} -> r }\n";
        var p = check(src);
        assertThat(p).anyMatch(x -> x.severity() == ScriptValidator.Severity.ERROR && x.message().contains("Payer") && x.message().contains("in [Researcher, Writer]"));
    }

    @Test
    void anAllowListOrAnEnumBoundsIt() {
        String payer = AGENTS + "tool Pay { use: webhook url: \"https://example.com/h\" effect: changes }\n"
                + "agent Payer { model: \"m\" system: \"pay\" tools: [Pay] approve: [Pay] }\n";
        assertThat(check(payer + "workflow Main(task) { delegate \"x\" to {task.owner} in [Researcher, Writer] -> r }\n"))
                .noneMatch(x -> x.message().contains("a model chooses which agent runs"));
        assertThat(check(payer + "agent Planner { model: \"m\" system: \"plan\" output_schema: { owner: enum[\"Researcher\", \"Writer\"] } }\n"
                        + "workflow Main(q) { delegate \"plan\" to Planner -> task\n  delegate \"x\" to {task.owner} -> r }\n"))
                .noneMatch(x -> x.message().contains("a model chooses which agent runs"));
    }

    // ---- the audit

    List<Finding> la22(String source) {
        AuditReport r = SecurityAudit.audit(parse(source), "t.loom");
        return r.findings().stream().filter(f -> f.rule().equals("LA22")).toList();
    }

    @Test
    void laTwentyTwoIsGradedByTheMostDangerousAgentItCouldReach() {
        String payer = AGENTS + "tool Pay { use: webhook url: \"https://example.com/h\" effect: changes }\n"
                + "agent Payer { model: \"m\" system: \"pay\" tools: [Pay] approve: [Pay] }\n";
        assertThat(la22(payer + "workflow Main(t) { delegate \"x\" to {t.owner} -> r }\n"))
                .singleElement().satisfies(f -> assertThat(f.severity().name()).isEqualTo("HIGH"));
        assertThat(la22(payer + "workflow Main(t) { delegate \"x\" to {t.owner} in [Writer] -> r }\n"))
                .singleElement().satisfies(f -> assertThat(f.severity().name()).isEqualTo("LOW"));
        assertThat(la22(AGENTS + "workflow Main(t) { delegate \"x\" to {t.owner} -> r }\n"))
                .singleElement().satisfies(f -> assertThat(f.severity().name()).isEqualTo("LOW"));
        assertThat(la22(AGENTS + "workflow Main(t) { delegate \"x\" to Writer -> r }\n")).isEmpty();
    }

    // ---- at run time

    record Out(Throwable error, Map<String, Object> vars, List<TraceEvent> events) {}

    Out run(String source, Map<String, String> inputs) {
        LoomScript s = parse(source);
        io.github.llm4j.LLMClient client =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"answer\"}\n```").model("t").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        HarnessExecutor ex = new HarnessExecutor(s, new ToolRegistry(), m -> client);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        ex.addTraceListener(events::add);
        ex.setStaticSinkChecks(false);
        ex.initialize();
        Throwable error = null;
        try {
            ex.executeWorkflow("Main", inputs);
        } catch (Throwable t) {
            error = t;
        }
        return new Out(error, ex.getContext().getAll(), events);
    }

    @Test
    void aNameOutsideTheListFailsTheStepAndTheTraceNamesTheStepNotTheValue() {
        Out o = run(AGENTS + "workflow Main(owner) { delegate \"x\" to {owner} in [Researcher, Writer] -> r\n  note \"after\" }\n", Map.of("owner", "Editor"));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().getMessage()).contains("Editor").contains("allows Researcher, Writer");
        var e = o.events().stream().filter(t -> t.type().equals(TraceEvent.ROUTE_REFUSED)).findFirst().orElseThrow();
        assertThat(String.valueOf(e.data())).doesNotContain("Editor");
    }

    @Test
    void aNameInTheListRuns() {
        Out o = run(AGENTS + "workflow Main(owner) { delegate \"x\" to {owner} in [Researcher, Writer] -> r }\n", Map.of("owner", "Writer"));
        assertThat(o.error()).isNull();
        assertThat(o.vars().get("r")).isEqualTo("answer");
    }

    @Test
    void onFailureHandlesARefusedRoute() {
        Out o = run(AGENTS + "workflow Main(owner) { delegate \"x\" to {owner} in [Writer] -> r on_failure { note \"refused: {_error}\" } }\n", Map.of("owner", "Editor"));
        assertThat(o.error()).isNull();
        assertThat(o.events().stream().anyMatch(t -> t.type().equals(TraceEvent.NOTE) && t.text().startsWith("refused:"))).isTrue();
    }

    @Test
    void theMapFormStoresEachAnswerUnderItsKeyInOneVariable() {
        Out o = run(AGENTS + "workflow Main(a, b) {\n  delegate \"x\" to Writer -> results[a]\n  delegate \"y\" to Writer -> results[b]\n  set both = \"{results.first} {results.second}\"\n}\n", Map.of("a", "first", "b", "second"));
        assertThat(o.error()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> results = (Map<String, Object>) o.vars().get("results");
        assertThat(results.keySet()).containsExactlyInAnyOrder("first", "second");
        assertThat(o.vars().get("both")).isEqualTo("answer answer");
    }

    @Test
    void aKeyThatIsNotAPlainWordIsRefused() {
        Out o = run(AGENTS + "workflow Main(a) { delegate \"x\" to Writer -> results[a] }\n", Map.of("a", "bad key!"));
        assertThat(o.error()).hasMessageContaining("not a plain word");
    }

    @Test
    void aDynamicResultNameCannotOverwriteAnythingTheScriptBinds() {
        for (String name : new String[] {"_secret", "q", "plan", "bad name"}) {
            Out o = run(AGENTS + "workflow Main(q, name) {\n  set plan = \"mine\"\n  delegate \"x\" to Writer -> {name}\n}\n", Map.of("q", "hi", "name", name));
            assertThat(o.error()).as(name).isNotNull().hasMessageContaining("Use the map form");
        }
        Out ok = run(AGENTS + "workflow Main(q, name) { delegate \"x\" to Writer -> {name} }\n", Map.of("q", "hi", "name", "fresh"));
        assertThat(ok.error()).isNull();
        assertThat(ok.vars().get("fresh")).isEqualTo("answer");
    }
}
