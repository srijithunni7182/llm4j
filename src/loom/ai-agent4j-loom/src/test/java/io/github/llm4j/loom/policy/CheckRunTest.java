package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
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
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 26b: {@code check … against … using …} end to end with scripted models. */
class CheckRunTest {

    @TempDir Path dir;

    static final String POLICY_CANARY = "POLICY-CANARY-9";
    static final String SHARED_CANARY = "SHARED-CANARY-5";
    static final String BAD = "We will refund you in full.";
    static final String GOOD = "Thanks, we are looking into it.";
    static final String CLEAN = "{\"no_promise\":{\"violated\":\"no\",\"reason\":\"fine\"}}";
    static final String BROKEN = "{\"no_promise\":{\"violated\":\"yes\",\"reason\":\"promises money\"}}";

    /** Who a request is for, told by its system prompt: writer, editor or judge. */
    static String who(LLMRequest r) {
        String all = r.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
        if (all.contains("You are a policy evaluator.")) return "judge";
        if (all.contains("You edit replies.")) return "editor";
        return "writer";
    }

    String script(String check, String extra) {
        return """
                shared_prompt: "SHARED-CANARY-5"
                evaluator Compliance { model: "judge" }
                policy HouseRules: "house-rules" checked by Compliance
                agent Writer { model: "writer" system: "You write replies." }
                agent Editor { model: "editor" system: "You edit replies." shared_prompt: none memory { transcript: none } }
                """
                + extra
                + "workflow Main(q) {\n  delegate \"{q}\" to Writer -> reply\n  "
                + check
                + "\n  note \"after: {reply}\"\n  return reply\n}\n";
    }

    record Outcome(List<LLMRequest> requests, List<TraceEvent> events, Map<String, Object> vars, Throwable error, String result) {
        List<String> notes() {
            return events.stream().filter(e -> e.type().equals(TraceEvent.NOTE)).map(TraceEvent::text).toList();
        }

        List<LLMRequest> to(String who) {
            return requests.stream().filter(r -> who(r).equals(who)).toList();
        }
    }

    Outcome run(String source, Function<LLMRequest, String> brain, HumanInterface human) throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(
                dir.resolve("policies/house-rules.md"),
                "# House\n" + POLICY_CANARY + " is only a description.\n\n## no_promise\nThe reply promises a refund.\n");
        List<LLMRequest> all = Collections.synchronizedList(new ArrayList<>());
        io.github.llm4j.LLMClient client =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        all.add(request);
                        String reply = brain.apply(request);
                        if (reply.startsWith("THROW")) throw new IllegalStateException(reply);
                        return LLMResponse.builder().content(reply).model("test").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        LoomScript parsed = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, new ToolRegistry(), model -> client);
        executor.setBaseDir(dir);
        if (human != null) executor.setHumanInterface(human);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        executor.initialize();
        Throwable error = null;
        String result = null;
        try {
            executor.executeWorkflow("Main", Map.of("q", "hello"));
            result = String.valueOf(executor.getContext().getAll().get("result"));
        } catch (Throwable t) {
            error = t;
        }
        return new Outcome(all, events, executor.getContext().getAll(), error, result);
    }

    /** Writer says BAD; the judge says BROKEN for BAD and CLEAN otherwise; the editor says GOOD. */
    static Function<LLMRequest, String> brain() {
        return r -> {
            String model = who(r);
            String text = r.getMessages().get(r.getMessages().size() - 1).getContent();
            if ("writer".equals(model)) return BAD;
            if ("editor".equals(model)) return GOOD;
            return text.contains(BAD) ? BROKEN : CLEAN;
        };
    }

    // ---- passing and failing

    @Test
    void aCleanTextPassesAndTheVerdictIsBound() throws Exception {
        Outcome o =
                run(
                        script("check reply against HouseRules using Compliance -> verdict", ""),
                        r -> "writer".equals(who(r)) ? GOOD : CLEAN,
                        null);
        assertThat(o.error()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> verdict = (Map<String, Object>) o.vars().get("verdict");
        assertThat(verdict).containsEntry("passed", "yes").containsEntry("action", "none");
        assertThat((List<?>) verdict.get("violated")).isEmpty();
    }

    @Test
    void noOnViolationMeansTheStepFailsAndTheErrorNamesTheRulesNeverTheText() throws Exception {
        Outcome o = run(script("check reply against HouseRules using Compliance", ""), brain(), null);
        assertThat(o.error()).isNotNull();
        assertThat(o.error().getMessage()).contains("no_promise").doesNotContain(BAD);
        assertThat(o.notes()).as("the workflow never got past the check").isEmpty();
    }

    @Test
    void onFailureRunsWhenTheStepFails() throws Exception {
        Outcome o =
                run(
                        script(
                                "check reply against HouseRules using Compliance on_failure { note \"held: {_error}\" }",
                                ""),
                        brain(),
                        null);
        assertThat(o.notes()).anyMatch(n -> n.startsWith("held:") && n.contains("no_promise") && !n.contains(BAD));
    }

    // ---- the actions

    @Test
    void replaceWithPutsTheFixedTextInThePlaceOfTheSubject() throws Exception {
        Outcome o =
                run(
                        script(
                                "check reply against HouseRules using Compliance -> verdict on_violation { replace with \"A colleague will reply.\" }",
                                ""),
                        brain(),
                        null);
        assertThat(o.error()).isNull();
        assertThat(o.result()).isEqualTo("A colleague will reply.");
        assertThat(((Map<?, ?>) o.vars().get("verdict")).get("action")).isEqualTo("replaced");
    }

    @Test
    void rewriteWithAnAgentReplacesTheTextAndChecksItAgainOnce() throws Exception {
        Outcome o =
                run(
                        script(
                                "check reply against HouseRules using Compliance -> verdict on_violation { rewrite with Editor }",
                                ""),
                        brain(),
                        null);
        assertThat(o.error()).isNull();
        assertThat(o.result()).isEqualTo(GOOD);
        assertThat(((Map<?, ?>) o.vars().get("verdict")).get("action")).isEqualTo("rewritten");
        assertThat(o.to("judge")).as("once before, once after the rewrite").hasSize(2);
    }

    @Test
    void aRewriteThatStillBreaksTheRuleFailsClosed() throws Exception {
        Outcome o =
                run(
                        script("check reply against HouseRules using Compliance on_violation { rewrite with Editor }", ""),
                        r -> "judge".equals(who(r)) ? BROKEN : "writer".equals(who(r)) ? BAD : "still " + BAD,
                        null);
        assertThat(o.error()).isNotNull();
        assertThat(o.error().getMessage()).contains("still breaks").contains("no_promise");
        assertThat(o.to("judge")).as("checked again once, no more").hasSize(2);
        assertThat(o.to("editor")).hasSize(1);
    }

    @Test
    void theRewriterSeesOnlyTheTextAndTheRulesBrokenNotTheSharedPromptOrTheTranscript() throws Exception {
        Outcome o =
                run(
                        script("check reply against HouseRules using Compliance on_violation { rewrite with Editor }", ""),
                        brain(),
                        null);
        String told = o.to("editor").get(0).getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
        assertThat(told).contains("You edit replies.").contains("no_promise").contains(BAD);
        assertThat(told).doesNotContain(SHARED_CANARY).doesNotContain("hello");
        assertThat(o.to("editor").get(0).getMessages()).hasSize(2);
    }

    @Test
    void anEvaluatorSeesNeitherTheSharedPromptNorThePolicyDescriptionOrTheTranscript() throws Exception {
        Outcome o = run(script("check reply against HouseRules using Compliance on_violation { replace with \"x\" }", ""), brain(), null);
        String told = o.to("judge").get(0).getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
        assertThat(told).contains("The reply promises a refund.").contains(BAD);
        assertThat(told).doesNotContain(SHARED_CANARY).doesNotContain(POLICY_CANARY).doesNotContain("hello");
    }

    @Test
    void policyTextNeverReachesAnAgentsPrompt() throws Exception {
        Outcome o = run(script("check reply against HouseRules using Compliance on_violation { replace with \"x\" }", ""), brain(), null);
        for (LLMRequest r : o.to("writer")) {
            String told = r.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(told).doesNotContain("promises a refund").doesNotContain(POLICY_CANARY);
        }
    }

    // ---- ask person

    static HumanInterface person(String answer, List<String> asked) {
        return new HumanInterface() {
            @Override
            public String promptHuman(String message) {
                asked.add(message);
                return answer;
            }
        };
    }

    @Test
    void askPersonSendAsIsKeepsTheTextAndAuditsTheOverride() throws Exception {
        List<String> asked = new ArrayList<>();
        Outcome o =
                run(
                        script("check reply against HouseRules using Compliance -> verdict on_violation { ask person }", ""),
                        brain(),
                        person("send as is", asked));
        assertThat(o.error()).isNull();
        assertThat(o.result()).isEqualTo(BAD);
        assertThat(asked.get(0)).contains(BAD).contains("no_promise");
        assertThat(((Map<?, ?>) o.vars().get("verdict")).get("action")).isEqualTo("asked");
    }

    @Test
    void askPersonSendInsteadUsesWhatThePersonTyped() throws Exception {
        Outcome o =
                run(
                        script("check reply against HouseRules using Compliance on_violation { ask person }", ""),
                        brain(),
                        person("Send this instead: We will look into it.", new ArrayList<>()));
        assertThat(o.result()).isEqualTo("We will look into it.");
    }

    @Test
    void askPersonStopFailsAndAnythingUnclearAlsoStops() throws Exception {
        for (String answer : new String[] {"stop", "maybe", ""}) {
            Outcome o =
                    run(
                            script("check reply against HouseRules using Compliance on_violation { ask person }", ""),
                            brain(),
                            person(answer, new ArrayList<>()));
            assertThat(o.error()).as(answer).isNotNull();
            assertThat(o.result()).isNull();
        }
    }

    @Test
    void askPersonWithNoWayToAskFailsClosed() throws Exception {
        Outcome o = run(script("check reply against HouseRules using Compliance on_violation { ask person }", ""), brain(), null);
        assertThat(o.error()).hasMessageContaining("HumanInterface");
    }

    // ---- an evaluator that cannot answer

    @Test
    void anUnavailableEvaluatorFailsByDefaultAndNothingGetsThrough() throws Exception {
        Outcome o =
                run(
                        script("check reply against HouseRules using Compliance", ""),
                        r -> "judge".equals(who(r)) ? "THROW down" : BAD,
                        null);
        assertThat(o.error()).hasMessageContaining("could not answer");
        assertThat(o.notes()).isEmpty();
    }

    @Test
    void onUnavailableReplaceWithGivesAHoldingAnswer() throws Exception {
        Outcome o =
                run(
                        script(
                                "check reply against HouseRules using Compliance -> verdict on_unavailable { replace with \"We are looking into this.\" }",
                                ""),
                        r -> "judge".equals(who(r)) ? "THROW down" : BAD,
                        null);
        assertThat(o.error()).isNull();
        assertThat(o.result()).isEqualTo("We are looking into this.");
    }

    @Test
    void aViolationIsNotTreatedAsUnavailable() throws Exception {
        Outcome o =
                run(
                        script(
                                "check reply against HouseRules using Compliance on_unavailable { replace with \"hold\" }",
                                ""),
                        brain(),
                        null);
        assertThat(o.error()).as("violation takes on_violation (fail), not on_unavailable").isNotNull();
    }

    @Test
    void retryAsksTheEvaluatorAgainWhenItCouldNotAnswer() throws Exception {
        int[] calls = {0};
        Outcome o =
                run(
                        script("check reply against HouseRules using Compliance retry 1", ""),
                        r -> {
                            if ("writer".equals(who(r))) return GOOD;
                            return calls[0]++ == 0 ? "THROW blip" : CLEAN;
                        },
                        null);
        assertThat(o.error()).isNull();
        assertThat(o.to("judge")).hasSize(2);
    }

    // ---- telemetry

    @Test
    void thePolicyCheckEventNamesRulesAndVersionsButNeverTheTextOrAReason() throws Exception {
        Outcome o =
                run(
                        script("check reply against HouseRules using Compliance on_violation { replace with \"x\" }", ""),
                        brain(),
                        null);
        TraceEvent e = o.events().stream().filter(t -> t.type().equals(TraceEvent.POLICY_CHECK)).findFirst().orElseThrow();
        String dump = String.valueOf(e.data()) + e.text();
        assertThat(dump).contains("no_promise").contains("HouseRules").contains("replaced");
        assertThat(dump).doesNotContain(BAD).doesNotContain("promises money");
        assertThat(e.data()).containsKeys("evaluator", "violated", "unsure", "action", "attempts");
    }

    @Test
    void aResumedRunReusesTheRecordedVerdictAndNeverAsksTheEvaluatorTwice() throws Exception {
        // Same executor, same journal: running the workflow again over its own journal must not call the judge.
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies/house-rules.md"), "## no_promise\nThe reply promises a refund.\n");
        int[] judged = {0};
        io.github.llm4j.LLMClient client =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        String m = who(request);
                        if ("judge".equals(m)) judged[0]++;
                        return LLMResponse.builder().content("judge".equals(m) ? CLEAN : GOOD).model("t").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        LoomScript parsed = new LoomParser(new Lexer(script("check reply against HouseRules using Compliance", "")).tokenize()).parseScript();
        HarnessExecutor first = new HarnessExecutor(parsed, new ToolRegistry(), model -> client);
        first.setBaseDir(dir);
        first.initialize();
        first.executeWorkflow("Main", Map.of("q", "hello"));
        assertThat(judged[0]).isEqualTo(1);
        HarnessExecutor second = new HarnessExecutor(parsed, new ToolRegistry(), model -> client);
        second.setBaseDir(dir);
        second.setJournal(first.getJournal());
        second.initialize();
        second.executeWorkflow("Main", Map.of("q", "hello"));
        assertThat(judged[0]).as("the recorded verdict was reused").isEqualTo(1);
    }
}
