package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 26c, D25: an agent's own calls to a tool that changes things are checked before they run. */
class ToolCallPolicyTest {

    @TempDir Path dir;

    static final String RULE_TEXT = "The reply promises a refund.";
    static final String BAD = "{\"to\": \"a@b.example\", \"body\": \"we will refund you\", \"copies\": 2}";
    static final String GOOD = "{\"to\": \"a@b.example\", \"body\": \"we are looking into it\", \"copies\": 2}";

    String script(String approve, String checkedBy) {
        return "evaluator Compliance { model: \"judge\" }\n"
                + "policy HouseRules: \"house-rules\" "
                + checkedBy
                + "\n"
                + "agent Assistant { model: \"agent\" system: \"You assist.\" tools: [Mailer] "
                + approve
                + " }\n"
                + "workflow Main(q) { delegate \"{q}\" to Assistant -> done\n  note \"done\" }\n";
    }

    record Run(Throwable error, List<TraceEvent> events, List<String> sent, List<String> observations, int asked) {}

    /** @param calls the tool-call JSON the agent makes on each of its turns; then it answers. */
    Run run(String source, List<String> calls, Function<String, String> judge, boolean approveYes) throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies/house-rules.md"), "## no_promise\n" + RULE_TEXT + "\n");
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        List<String> observations = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger turn = new AtomicInteger();
        AtomicInteger asked = new AtomicInteger();
        io.github.llm4j.LLMClient client =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        String all = request.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
                        if (all.contains("You are a policy evaluator.")) {
                            String text = request.getMessages().get(request.getMessages().size() - 1).getContent();
                            return LLMResponse.builder().content(judge.apply(text)).model("t").build();
                        }
                        int i = turn.getAndIncrement();
                        if (i > 0) observations.add(all);
                        String reply =
                                i < calls.size()
                                        ? "```json\n{\"thought\": \"t\", \"action\": \"Mailer\", \"action_input\": " + calls.get(i) + "}\n```"
                                        : "```json\n{\"thought\": \"t\", \"final_answer\": \"finished\"}\n```";
                        return LLMResponse.builder().content(reply).model("t").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        ToolRegistry tools = new ToolRegistry();
        tools.register(
                "Mailer",
                new Tool() {
                    @Override
                    public String getName() {
                        return "Mailer";
                    }

                    @Override
                    public String getDescription() {
                        return "sends mail";
                    }

                    @Override
                    public String execute(Map<String, Object> args) {
                        sent.add(String.valueOf(args.get("body")));
                        return "sent";
                    }
                });
        LoomScript parsed = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, tools, model -> client);
        executor.setBaseDir(dir);
        HumanInterface human =
                message -> {
                    asked.incrementAndGet();
                    return approveYes ? "yes" : "no";
                };
        executor.setHumanInterface(human);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        executor.initialize();
        Throwable error = null;
        try {
            executor.executeWorkflow("Main", Map.of("q", "send mail"));
        } catch (Throwable t) {
            error = t;
        }
        return new Run(error, events, sent, observations, asked.get());
    }

    static final Function<String, String> JUDGE =
            text ->
                    text.contains("refund")
                            ? "{\"no_promise\":{\"violated\":\"yes\",\"reason\":\"promises money\"}}"
                            : "{\"no_promise\":{\"violated\":\"no\",\"reason\":\"fine\"}}";

    @Test
    void aRefusedCallNeverRunsTheToolAndTheAgentLearnsRuleIdsAndReasonsNotRuleText() throws Exception {
        Run r = run(script("", "checked by Compliance"), List.of(BAD, GOOD), JUDGE, true);
        assertThat(r.error()).isNull();
        assertThat(r.sent()).as("only the second, clean call ran").containsExactly("we are looking into it");
        String told = String.join("\n", r.observations());
        assertThat(told).contains("no_promise").contains("promises money");
        assertThat(told).doesNotContain(RULE_TEXT);
    }

    @Test
    void aCleanCallRunsOnce() throws Exception {
        Run r = run(script("", "checked by Compliance"), List.of(GOOD), JUDGE, true);
        assertThat(r.sent()).containsExactly("we are looking into it");
    }

    @Test
    void refusalsAreBoundedAndAfterThemNothingRuns() throws Exception {
        Run r = run(script("", "checked by Compliance"), List.of(BAD, BAD.replace("refund you", "refund you today"), BAD.replace("refund you", "refund you soon"), BAD.replace("refund you", "refund you now")), JUDGE, true);
        assertThat(r.sent()).isEmpty();
        long refused = r.events().stream().filter(e -> e.type().equals(TraceEvent.TOOL_REFUSED_BY_POLICY)).count();
        assertThat(refused).as("refusals, the later ones telling the agent to stop").isEqualTo(4);
        assertThat(String.join("\n", r.observations())).contains("Stop:");
    }

    @Test
    void theEvaluatorRunsBeforeApprovalSoARefusedCallIsNeverPutToAPerson() throws Exception {
        Run refused = run(script("approve: [Mailer]", "checked by Compliance"), List.of(BAD), JUDGE, true);
        assertThat(refused.asked()).isZero();
        assertThat(refused.sent()).isEmpty();
        Run passed = run(script("approve: [Mailer]", "checked by Compliance"), List.of(GOOD), JUDGE, true);
        assertThat(passed.asked()).isEqualTo(1);
        assertThat(passed.sent()).hasSize(1);
        Run declined = run(script("approve: [Mailer]", "checked by Compliance"), List.of(GOOD), JUDGE, false);
        assertThat(declined.sent()).as("the person's no still refuses").isEmpty();
    }

    @Test
    void anEvaluatorThatCannotAnswerRefusesTheCall() throws Exception {
        Run r = run(script("", "checked by Compliance"), List.of(GOOD), text -> "THROW", true);
        assertThat(r.sent()).isEmpty();
    }

    @Test
    void aCallWithNoTextArgumentsIsNotSentToTheEvaluator() throws Exception {
        Run r = run(script("", "checked by Compliance"), List.of("{\"copies\": 3, \"urgent\": true}"), text -> { throw new AssertionError("evaluator must not be asked"); }, true);
        assertThat(r.sent()).hasSize(1);
    }

    @Test
    void theRefusalEventNamesRulesAndNeverTheArguments() throws Exception {
        Run r = run(script("", "checked by Compliance"), List.of(BAD), JUDGE, true);
        var e = r.events().stream().filter(t -> t.type().equals(TraceEvent.TOOL_REFUSED_BY_POLICY)).findFirst().orElseThrow();
        String dump = String.valueOf(e.data()) + e.text();
        assertThat(dump).contains("Mailer").contains("no_promise").doesNotContain("refund you").doesNotContain("a@b.example");
    }

    @Test
    void aPolicyWithoutCheckedByIsAnErrorWhenAnAgentHoldsAChangingTool() throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies/house-rules.md"), "## no_promise\n" + RULE_TEXT + "\n");
        LoomScript parsed = new LoomParser(new Lexer(script("", "")).tokenize()).parseScript();
        ToolRegistry tools = new ToolRegistry();
        var problems = new ScriptValidator().validate(parsed, new ScriptValidator.Context().baseDir(dir).registeredTools(java.util.Set.of("Mailer")));
        assertThat(problems)
                .anyMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && p.message().contains("which changes things; name the evaluator"));
    }

    @Test
    void textArgumentsAreStringsAtAnyDepthAndNumbersAndFlagsAreNot() {
        // ToolCallPolicy judges what a call says, never how much it moves
        String text = io.github.llm4j.loom.execution.PolicyToolHookAccess.textOf(Map.of("body", "hi", "n", 5, "flag", true, "list", List.of("a", "b")));
        assertThat(text).contains("body: hi").contains("a").contains("b").doesNotContain("5").doesNotContain("true");
    }
}
