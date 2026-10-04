package io.github.llm4j.toolcalling;

import static io.github.llm4j.toolcalling.Scripted.call;
import static io.github.llm4j.toolcalling.Scripted.calls;
import static io.github.llm4j.toolcalling.Scripted.text;
import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.ReActAgent.ToolCalling;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.toolcalling.Scripted.Recorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TCA-*: the agent loop on native tool calling, and the choice between it and the text protocol. */
class NativeAgentTest {

    ReActAgent.Builder agent(Scripted model, Recorder... tools) {
        ReActAgent.Builder b = ReActAgent.builder().llmClient(model).maxIterations(6);
        for (Recorder t : tools) b.addTool(t);
        return b;
    }

    // ── the loop ────────────────────────────────────────────────────────

    @Test
    void tca03_aReplyWithNoToolCallsIsTheFinalAnswer() {
        Scripted model = Scripted.nativeModel(text("It is 4."));
        AgentResult r = agent(model, new Recorder("calc", "4")).build().run("2+2?");
        assertEquals("It is 4.", r.getFinalAnswer());
        assertTrue(r.isCompleted());
        assertTrue(r.isProtocolFollowed());
        assertTrue(r.getSteps().isEmpty());
        assertEquals(1, r.getIterations());
        LLMRequest sent = model.requests.get(0);
        assertEquals(1, sent.getTools().size(), "the tools go out as definitions");
        assertEquals("calc", sent.getTools().get(0).name());
        assertFalse(sent.getMessages().get(0).getContent().contains("```json"), "no JSON protocol in a native prompt");
        assertEquals("2+2?", sent.getMessages().get(1).getContent());
    }

    @Test
    void tca03_aToolCallRunsTheToolAndItsResultGoesBackAsAToolMessage() {
        Recorder calc = new Recorder("calc", "4");
        Scripted model = Scripted.nativeModel(
                calls("I'll add them", call("c1", "calc", Map.of("expression", "2+2", "n", 3, "nested", Map.of("k", List.of(1, 2))))),
                text("The answer is 4."));
        AgentResult r = agent(model, calc).build().run("2+2?");

        assertEquals("The answer is 4.", r.getFinalAnswer());
        assertEquals(2, r.getIterations());
        assertEquals(1, r.getSteps().size());
        AgentResult.AgentStep step = r.getSteps().get(0);
        assertEquals("calc", step.getAction());
        assertEquals("4", step.getObservation());
        assertEquals(AgentResult.StepOutcome.EXECUTED, step.getOutcome());
        assertEquals("I'll add them", step.getThought());
        assertEquals(Map.of("expression", "2+2", "n", 3, "nested", Map.of("k", List.of(1, 2))), calc.seen.get(0), "arguments arrive as the model's JSON, unchanged");

        List<Message> second = model.requests.get(1).getMessages();
        assertEquals(4, second.size(), "system, question, the assistant's call, the result");
        assertEquals(Message.Role.ASSISTANT, second.get(2).getRole());
        assertEquals("calc", second.get(2).getToolCalls().get(0).name());
        assertEquals("c1", second.get(2).getToolCalls().get(0).id());
        assertEquals(Message.Role.TOOL, second.get(3).getRole());
        assertEquals("c1", second.get(3).getToolCallId());
        assertEquals("calc", second.get(3).getName());
        assertEquals("4", second.get(3).getContent());
        assertEquals(1, model.requests.get(1).getTools().size(), "the tools are offered every turn");
    }

    @Test
    void tca03_severalCallsInOneTurnRunInOrderAndEachGetsItsOwnResult() {
        Recorder a = new Recorder("alpha", "A!");
        Recorder b = new Recorder("beta", "B!");
        Scripted model = Scripted.nativeModel(
                calls("", call(null, "alpha", Map.of("x", 1)), call(null, "beta", Map.of("y", 2))),
                text("done"));
        AgentResult r = agent(model, a, b).build().run("go");
        assertEquals(List.of("alpha", "beta"), r.getSteps().stream().map(AgentResult.AgentStep::getAction).toList());
        List<Message> second = model.requests.get(1).getMessages();
        assertEquals(5, second.size(), "system, question, assistant, two results");
        String id1 = second.get(2).getToolCalls().get(0).id();
        String id2 = second.get(2).getToolCalls().get(1).id();
        assertNotNull(id1, "calls that arrive without ids are given ones");
        assertNotEquals(id1, id2);
        assertEquals(id1, second.get(3).getToolCallId());
        assertEquals("A!", second.get(3).getContent());
        assertEquals(id2, second.get(4).getToolCallId());
        assertEquals("B!", second.get(4).getContent());
        assertEquals(1, r.getSteps().get(0).getThought() == null ? 1 : 1);
        assertNull(r.getSteps().get(0).getThought(), "no text came with the calls");
    }

    @Test
    void tca04_everyCallGetsAResultEvenWhenItDidNotRun() {
        Recorder calc = new Recorder("calc", "4");
        Recorder risky = new Recorder("risky", "sent");
        risky.needsApproval = true;
        Scripted model = Scripted.nativeModel(
                calls("", call("c1", "nope", Map.of()), call("c2", "calc", Map.of("e", "1")), call("c3", "calc", Map.of("e", "1")), call("c4", "risky", Map.of())),
                text("done"));
        AgentResult r = agent(model, calc, risky).approvalCallback((tool, args, thought) -> false).build().run("go");

        assertEquals(List.of(AgentResult.StepOutcome.UNKNOWN_TOOL, AgentResult.StepOutcome.EXECUTED, AgentResult.StepOutcome.DUPLICATE_BLOCKED,
                AgentResult.StepOutcome.REJECTED_BY_HUMAN), r.getSteps().stream().map(AgentResult.AgentStep::getOutcome).toList());
        List<Message> second = model.requests.get(1).getMessages();
        List<Message> results = second.stream().filter(m -> m.getRole() == Message.Role.TOOL).toList();
        assertEquals(List.of("c1", "c2", "c3", "c4"), results.stream().map(Message::getToolCallId).toList(), "the transcript stays valid");
        assertTrue(results.get(0).getContent().contains("Unknown tool"));
        assertTrue(results.get(2).getContent().contains("already taken this action"));
        assertTrue(results.get(3).getContent().contains("rejected"));
        assertTrue(risky.seen.isEmpty(), "a rejected call never runs");
    }

    @Test
    void tca04_aToolThatNeedsApprovalWithNoCallbackIsBlockedAndAThrowingToolIsAnError() {
        Recorder risky = new Recorder("risky", "sent");
        risky.needsApproval = true;
        Recorder boom = new Recorder("boom", "x") {
            @Override public String execute(Map<String, Object> args) { throw new IllegalStateException("kaboom"); }
        };
        Scripted model = Scripted.nativeModel(calls("", call("c1", "risky", Map.of()), call("c2", "boom", Map.of())), text("done"));
        AgentResult r = agent(model, risky, boom).build().run("go");
        assertEquals(AgentResult.StepOutcome.APPROVAL_UNAVAILABLE, r.getSteps().get(0).getOutcome());
        assertEquals(AgentResult.StepOutcome.EXECUTION_ERROR, r.getSteps().get(1).getOutcome());
        assertTrue(r.getSteps().get(1).getObservation().contains("kaboom"));
        assertTrue(r.isCompleted());
    }

    @Test
    void tca04_approvedCallsRunWithTheModelsArguments() {
        Recorder risky = new Recorder("risky", "sent");
        risky.needsApproval = true;
        List<String> asked = new ArrayList<>();
        Scripted model = Scripted.nativeModel(calls("sending now", call("c1", "risky", Map.of("to", "a"))), text("done"));
        AgentResult r = agent(model, risky).approvalCallback((tool, args, thought) -> {
            asked.add(tool + args + thought);
            return true;
        }).build().run("go");
        assertEquals(List.of("risky{to=a}sending now"), asked);
        assertEquals(Map.of("to", "a"), risky.seen.get(0));
        assertEquals(AgentResult.StepOutcome.EXECUTED, r.getSteps().get(0).getOutcome());
    }

    @Test
    void tca05_aToolWithNoDeclaredParametersGetsItsJsonBackOutOfTheFreeFormInput() {
        Recorder legacy = new Recorder("legacy", "ok");
        Scripted model = Scripted.nativeModel(
                calls("", call("c1", "legacy", Map.of("input", "{\"orderId\": \"A-17\", \"amount\": 40}"))),
                calls("", call("c2", "legacy", Map.of("input", "just text"))),
                text("done"));
        agent(model, legacy).build().run("go");
        assertEquals(Map.of("orderId", "A-17", "amount", 40), legacy.seen.get(0), "Gemini is offered one string input; the object inside is unwrapped");
        assertEquals(Map.of("input", "just text"), legacy.seen.get(1), "anything else is passed as is");
    }

    @Test
    void tca05_aToolThatDeclaresItsParametersIsNeverUnwrapped() {
        Recorder declared = new Recorder("declared", "ok");
        declared.schema = ToolSchema.object().string("input", "the input", true).build();
        Scripted model = Scripted.nativeModel(calls("", call("c1", "declared", Map.of("input", "{\"a\":1}"))), text("done"));
        agent(model, declared).build().run("go");
        assertEquals(Map.of("input", "{\"a\":1}"), declared.seen.get(0));
        assertEquals("input", ((Map<?, ?>) model.requests.get(0).getTools().get(0).parameters().get("properties")).keySet().iterator().next());
    }

    @Test
    void tca06_anEmptyReplyIsNudgedNotAccepted() {
        Scripted model = Scripted.nativeModel(text(""), text("  \n"), text("finally"));
        AgentResult r = agent(model, new Recorder("calc", "4")).build().run("go");
        assertEquals("finally", r.getFinalAnswer());
        assertEquals(3, r.getIterations(), "each empty reply used an iteration");
        List<Message> third = model.requests.get(2).getMessages();
        assertEquals(Message.Role.USER, third.get(third.size() - 1).getRole());
        assertTrue(third.get(third.size() - 1).getContent().contains("empty"));
    }

    @Test
    void tca03_theIterationLimitStillStopsALoopThatNeverAnswers() {
        Recorder calc = new Recorder("calc", "4");
        LLMResponse[] forever = new LLMResponse[3];
        for (int i = 0; i < forever.length; i++) forever[i] = calls("", call("c" + i, "calc", Map.of("n", i)));
        AgentResult r = agent(Scripted.nativeModel(forever), calc).maxIterations(3).build().run("go");
        assertFalse(r.isCompleted());
        assertEquals(3, r.getSteps().size());
        assertTrue(r.getFinalAnswer().contains("Maximum iterations"));
    }

    @Test
    void tca03_listenersAuditAndUsageWorkAsOnTheTextProtocol() {
        List<String> events = new ArrayList<>();
        Scripted model = Scripted.nativeModel(calls("planning", call("c1", "calc", Map.of("e", "1"))), text("ok"));
        AgentResult r = agent(model, new Recorder("calc", "4")).addListener(new AgentEventListener() {
            public void onThought(String t) { events.add("thought:" + t); }
            public void onAction(String tool, String input) { events.add("action:" + tool + ":" + input); }
            public void onObservation(String o) { events.add("observation:" + o); }
        }).build().run("go");
        assertEquals(List.of("thought:planning", "action:calc:{\"e\":\"1\"}", "observation:4"), events);
        assertEquals(2, r.getUsage().getLlmCalls());
        assertEquals(30, r.getUsage().getTotalTokens());
        assertNotNull(r.getConfidence());
    }

    @Test
    void tca03_budgetsApplyAndTheToolCallsSurviveBudgeting() {
        Recorder calc = new Recorder("calc", "4");
        Scripted model = Scripted.nativeModel(calls("", call("c1", "calc", Map.of("e", "1"))), text("done"));
        AgentResult r = agent(model, calc).budget(Budget.builder().tokens(1_000_000).build()).build().run("go");
        assertEquals("done", r.getFinalAnswer());
        assertEquals(1, calc.seen.size(), "the budget wrapper did not drop the call");
        assertTrue(model.requests.get(0).getTools().size() == 1, "nor the tools");

        // a tiny budget stops the run before the model is called, as on the text protocol
        Scripted second = Scripted.nativeModel(text("never reached"));
        AgentResult stopped = agent(second, calc).budget(Budget.builder().tokens(1).build()).build().run("go");
        assertFalse(stopped.isCompleted());
        assertTrue(second.requests.isEmpty());
    }

    // ── choosing the mode (TCA-01, TCA-02) ──────────────────────────────

    @Test
    void tca02_autoUsesNativeWhenTheClientSupportsItAndTheTextProtocolOtherwise() {
        Scripted capable = Scripted.nativeModel(text("x"));
        agent(capable, new Recorder("calc", "4")).build().run("hi");
        assertEquals(1, capable.requests.get(0).getTools().size());

        Scripted plain = Scripted.textModel("```json\n{\"plan\":\"p\",\"final_answer\":\"x\"}\n```");
        agent(plain, new Recorder("calc", "4")).build().run("hi");
        assertTrue(plain.requests.get(0).getTools().isEmpty(), "a client without support never sees tools");
        assertTrue(plain.requests.get(0).getMessages().get(0).getContent().contains("```json"), "and gets the text protocol prompt");
    }

    @Test
    void tca01_textModeKeepsTheOldLoopEvenWithACapableClient() {
        Scripted capable = Scripted.nativeModel(text("```json\n{\"plan\":\"p\",\"final_answer\":\"x\"}\n```"));
        AgentResult r = agent(capable, new Recorder("calc", "4")).toolCalling(ToolCalling.TEXT).build().run("hi");
        assertEquals("x", r.getFinalAnswer());
        assertTrue(capable.requests.get(0).getTools().isEmpty());
    }

    @Test
    void tca02_nativeInsistsAndSaysWhyWhenItCannot() {
        IllegalStateException unsupported = assertThrows(IllegalStateException.class,
                () -> agent(Scripted.textModel("x"), new Recorder("calc", "4")).toolCalling(ToolCalling.NATIVE).build());
        assertTrue(unsupported.getMessage().contains("supports tool calling"), unsupported.getMessage());
        IllegalStateException noTools = assertThrows(IllegalStateException.class,
                () -> agent(Scripted.nativeModel()).toolCalling(ToolCalling.NATIVE).build());
        assertTrue(noTools.getMessage().contains("at least one tool"));
        IllegalStateException badName = assertThrows(IllegalStateException.class,
                () -> agent(Scripted.nativeModel(), new Recorder("has space", "x")).toolCalling(ToolCalling.NATIVE).build());
        assertTrue(badName.getMessage().contains("has space"), badName.getMessage());
    }

    @Test
    void tca02_autoFallsBackToTextForIllegalNamesNoToolsAndACustomPrompt() {
        String finalJson = "```json\n{\"plan\":\"p\",\"final_answer\":\"x\"}\n```";
        Scripted illegal = new Scripted(true, text(finalJson));
        agent(illegal, new Recorder("web search", "x")).build().run("hi");
        assertTrue(illegal.requests.get(0).getTools().isEmpty(), "a name Gemini and Claude would refuse keeps the text protocol");

        Scripted none = new Scripted(true, text(finalJson));
        agent(none).build().run("hi");
        assertTrue(none.requests.get(0).getTools().isEmpty());

        Scripted custom = new Scripted(true, text(finalJson));
        agent(custom, new Recorder("calc", "4")).systemPrompt("Answer in the JSON block format. Tools: {tool_names}").build().run("hi");
        assertTrue(custom.requests.get(0).getTools().isEmpty(), "a prompt of your own decides the protocol");

        Scripted forced = Scripted.nativeModel(text("native answer"));
        agent(forced, new Recorder("calc", "4")).systemPrompt("My own prompt").toolCalling(ToolCalling.NATIVE).build().run("hi");
        assertEquals(1, forced.requests.get(0).getTools().size());
        assertEquals("My own prompt", forced.requests.get(0).getMessages().get(0).getContent(), "NATIVE uses the prompt as given");
    }

    @Test
    void tca07_theNativePromptCarriesInstructionsPersonaAndSkillsButNoProtocol() {
        Scripted model = Scripted.nativeModel(text("x"));
        agent(model, new Recorder("calc", "4")).instructions("Be formal.").persona(AgentPersona.builder().name("Asha").role("analyst").build()).build().run("hi");
        String system = model.requests.get(0).getMessages().get(0).getContent();
        assertTrue(system.startsWith("Be formal."), system);
        assertTrue(system.contains("Asha"));
        assertTrue(system.contains("reply directly in plain text"));
        assertFalse(system.contains("final_answer"));
        assertFalse(system.contains("action_input"));
    }

    @Test
    void tca01_toBuilderKeepsTheModeAndTheNativePrompt() {
        Scripted model = Scripted.nativeModel(text("x"), text("y"), text("z"));
        ReActAgent base = agent(model, new Recorder("calc", "4")).instructions("Be formal.").build();
        ReActAgent copy = base.toBuilder().maxIterations(3).build();
        copy.run("hi");
        assertEquals(1, model.requests.get(0).getTools().size(), "still native");
        assertTrue(model.requests.get(0).getMessages().get(0).getContent().startsWith("Be formal."), "with the same prompt, not the text one");

        Scripted text = Scripted.textModel("```json\n{\"plan\":\"p\",\"final_answer\":\"x\"}\n```");
        ReActAgent forcedText = agent(text, new Recorder("calc", "4")).toolCalling(ToolCalling.TEXT).build();
        forcedText.toBuilder().build().run("hi");
        assertTrue(text.requests.get(0).getTools().isEmpty());
    }

    @Test
    void tca02_toolSpecsComeFromTheToolsDeclaredSchemas() {
        Recorder calc = new Recorder("calc", "4");
        calc.schema = ToolSchema.object().string("expression", "what to compute", true).build();
        Scripted model = Scripted.nativeModel(text("x"));
        agent(model, calc).build().run("hi");
        ToolSpec spec = model.requests.get(0).getTools().get(0);
        assertEquals("test tool calc", spec.description());
        assertEquals(List.of("expression"), spec.parameters().get("required"));
    }
}
