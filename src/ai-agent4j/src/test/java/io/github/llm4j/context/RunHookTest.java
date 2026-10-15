package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.RunHook;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks at the start of a run, before each model call and before the agent settles; both loops (native tool calls and the text protocol). */
class RunHookTest {

    private static String everything(LLMRequest request) {
        StringBuilder all = new StringBuilder();
        for (Message m : request.getMessages()) all.append(m.getContent()).append('\n');
        return all.toString();
    }

    private static ScriptedModel answering(boolean nativeCalling, String... answers) {
        return ScriptedModel.by(nativeCalling, true, n -> nativeCalling
                ? ScriptedModel.answer(answers[Math.min(n, answers.length - 1)])
                : ScriptedModel.textAnswer(answers[Math.min(n, answers.length - 1)]));
    }

    private static final class Recorder implements AgentEventListener {
        final List<String> events = new ArrayList<>();
        public void onThought(String t) { }
        public void onAction(String n, String i) { }
        public void onObservation(String o) { }
        @Override public void onRunHook(String seam, String outcome, String message) { events.add(seam + ":" + outcome + ":" + message); }
    }

    // ---- the question ----

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aHookCanRefuseTheQuestionBeforeAnyModelCall(boolean nativeCalling) {
        ScriptedModel model = answering(nativeCalling, "never reached");
        Recorder events = new Recorder();
        AgentResult r = ReActAgent.builder().llmClient(model).addListener(events).addRunHook(new RunHook() {
            @Override public Input onInput(String q) { return q.contains("password") ? Input.reject("Passwords may not be sent to this agent.") : Input.allow(); }
        }).build().run("my password is hunter2");

        assertThat(r.isInputRejected()).isTrue();
        assertThat(r.isCompleted()).isFalse();
        assertThat(r.getFinalAnswer()).isEqualTo("Passwords may not be sent to this agent.");
        assertThat(model.requests).isEmpty();
        assertThat(events.events).containsExactly("input:rejected:Passwords may not be sent to this agent.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aHookCanRewriteTheQuestionAndLaterHooksSeeTheRewrite(boolean nativeCalling) {
        ScriptedModel model = answering(nativeCalling, "ok");
        List<String> seen = new ArrayList<>();
        ReActAgent.builder().llmClient(model)
                .addRunHook(new RunHook() { @Override public Input onInput(String q) { return Input.replace(q.replace("4111-1111", "[card]")); } })
                .addRunHook(new RunHook() { @Override public Input onInput(String q) { seen.add(q); return Input.allow(); } })
                .build().run("pay with 4111-1111");

        assertThat(seen).containsExactly("pay with [card]");
        assertThat(everything(model.requests.get(0))).contains("pay with [card]").doesNotContain("4111-1111");
    }

    @Test
    void aHookThatThrowsRefusesTheQuestionRatherThanLettingItThrough() {
        ScriptedModel model = answering(true, "never reached");
        AgentResult r = ReActAgent.builder().llmClient(model).addRunHook(new RunHook() {
            @Override public Input onInput(String q) { throw new IllegalStateException("checker down"); }
        }).build().run("hello");
        assertThat(r.isInputRejected()).isTrue();
        assertThat(model.requests).isEmpty();
    }

    // ---- before each model call ----

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aNoteIsShownInThatCallOnlyAndIsNotKept(boolean nativeCalling) {
        ScriptedModel model = ScriptedModel.by(nativeCalling, true, n -> n == 0
                ? (nativeCalling ? ScriptedModel.calls("looking", ScriptedModel.call("c0", "fixed", java.util.Map.of()))
                                 : ScriptedModel.textStep("looking", "fixed", "{}"))
                : (nativeCalling ? ScriptedModel.answer("done") : ScriptedModel.textAnswer("done")));
        List<Integer> remaining = new ArrayList<>();
        AgentResult r = ReActAgent.builder().llmClient(model).addTool(new ScriptedModel.FixedTool("fixed", "result"))
                .addRunHook(new RunHook() {
                    @Override public ModelCall beforeModelCall(CallView view) {
                        remaining.add(view.remaining());
                        return view.iteration() == 0 ? ModelCall.withNote("Reminder: be brief.") : ModelCall.proceed();
                    }
                }).maxIterations(5).build().run("go");

        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(everything(model.requests.get(0))).contains("Reminder: be brief.");
        assertThat(everything(model.requests.get(1))).doesNotContain("Reminder: be brief.");
        assertThat(remaining).containsExactly(5, 4);
    }

    // ---- before the agent settles ----

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anAnswerCanBeSentBackWithAMessageTheModelReads(boolean nativeCalling) {
        ScriptedModel model = answering(nativeCalling, "first try", "second try");
        Recorder events = new Recorder();
        AgentResult r = ReActAgent.builder().llmClient(model).addListener(events).addRunHook(new RunHook() {
            @Override public Settle beforeSettle(String answer, List<AgentResult.AgentStep> steps) {
                return answer.equals("first try") ? Settle.sendBack("the tests have not been run") : Settle.accept();
            }
        }).build().run("fix it");

        assertThat(r.getFinalAnswer()).isEqualTo("second try");
        assertThat(model.requests).hasSize(2);
        assertThat(everything(model.requests.get(1))).contains("Your answer was not accepted: the tests have not been run");
        assertThat(events.events).containsExactly("settle:sent_back:the tests have not been run");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aHookCanNeverKeepTheAgentFromFinishing(boolean nativeCalling) {
        ScriptedModel model = answering(nativeCalling, "again");
        Recorder events = new Recorder();
        AgentResult r = ReActAgent.builder().llmClient(model).addListener(events).maxSettleRetries(2).maxIterations(10).addRunHook(new RunHook() {
            @Override public Settle beforeSettle(String answer, List<AgentResult.AgentStep> steps) { return Settle.sendBack("no"); }
        }).build().run("go");

        assertThat(r.isCompleted()).isTrue();
        assertThat(r.getFinalAnswer()).isEqualTo("again");
        assertThat(model.requests).hasSize(3); // sent back twice, then accepted
        assertThat(events.events).containsExactly("settle:sent_back:no", "settle:sent_back:no", "settle:limit_reached:no");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aHookCanReplaceTheAnswer(boolean nativeCalling) {
        ScriptedModel model = answering(nativeCalling, "the key is sk-123");
        AgentResult r = ReActAgent.builder().llmClient(model).addRunHook(new RunHook() {
            @Override public Settle beforeSettle(String answer, List<AgentResult.AgentStep> steps) { return Settle.replace(answer.replace("sk-123", "[removed]")); }
        }).build().run("go");
        assertThat(r.getFinalAnswer()).isEqualTo("the key is [removed]");
    }

    @Test
    void aHookThatThrowsWhileSettlingNeverLetsTheAnswerThroughAndTheRunDoesNotComplete() {
        ScriptedModel model = answering(true, "answer");
        AgentResult r = ReActAgent.builder().llmClient(model).maxIterations(3).addRunHook(new RunHook() {
            @Override public Settle beforeSettle(String answer, List<AgentResult.AgentStep> steps) { throw new IllegalStateException("checker down"); }
        }).build().run("go");
        assertThat(r.isCompleted()).isFalse();
        assertThat(r.getFinalAnswer()).isEqualTo("Maximum iterations reached without finding a final answer.");
        assertThat(model.requests).hasSize(3); // the model was told each time and tried again, until the steps ran out
    }

    @Test
    void aHookThatThrowsBeforeAModelCallEndsTheRunWithTheError() {
        ScriptedModel model = answering(true, "answer");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> ReActAgent.builder().llmClient(model).addRunHook(new RunHook() {
            @Override public ModelCall beforeModelCall(CallView view) { throw new IllegalStateException("checker down"); }
        }).build().run("go"));
        assertThat(model.requests).isEmpty();
    }

    @Test
    void withNoHooksNothingChanges() {
        ScriptedModel model = answering(true, "plain");
        AgentResult r = ReActAgent.builder().llmClient(model).build().run("go");
        assertThat(r.getFinalAnswer()).isEqualTo("plain");
        assertThat(r.isInputRejected()).isFalse();
    }
}
