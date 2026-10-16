package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.SuperviseDef;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** {@code supervise Agent { when ... }}: how it parses, what it checks, and that a rule acts on the step it watches. */
class SuperviseTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    // ---- parsing ----

    @Test
    void aBlockHoldsRulesWithTheirActions() {
        SuperviseDef def = parse("""
                agent R { model: "m" tools: [Search] }
                supervise R {
                    when looping(3)              { steer "Use a different source." once }
                    when tool_failing(Search, 2) { wrap_up }
                    when context > 90%           { cancel use partial }
                    when steps > 20              { cancel }
                }
                """).getSupervisions().get(0);
        assertEquals("R", def.getAgent());
        assertEquals(4, def.getRules().size());
        var loop = def.getRules().get(0);
        assertEquals(SuperviseDef.Signal.LOOPING, loop.getSignal());
        assertEquals(3, loop.getN());
        assertTrue(loop.isOnce());
        assertEquals("Use a different source.", loop.getActions().get(0).text());
        assertEquals("Search", def.getRules().get(1).getTool());
        assertEquals(SuperviseDef.Do.WRAP_UP, def.getRules().get(1).getActions().get(0).kind());
        assertEquals(90, def.getRules().get(2).getN());
        assertEquals(io.github.llm4j.loom.ast.OutcomeStmt.Kind.USE_PARTIAL, def.getRules().get(2).getActions().get(0).outcome());
        assertNull(def.getRules().get(3).getActions().get(0).outcome());
        assertEquals("tool_failing(Search, 2)", def.getRules().get(1).describe());
    }

    @Test
    void whatIsNotSupportedOrNotUnderstoodIsRefusedInPlainWords() {
        String retry = assertThrows(RuntimeException.class, () -> parse("supervise R { when looping(2) { retry with B } }")).getMessage();
        assertTrue(retry.contains("not supported in supervise yet"), retry);
        String signal = assertThrows(RuntimeException.class, () -> parse("supervise R { when sadness(2) { wrap_up } }")).getMessage();
        assertTrue(signal.contains("looping(N)") && signal.contains("tool_failing"), signal);
        assertThrows(RuntimeException.class, () -> parse("supervise R { when looping(0) { wrap_up } }"));
        assertThrows(RuntimeException.class, () -> parse("supervise R { when context > 120% { wrap_up } }"));
        assertThrows(RuntimeException.class, () -> parse("supervise R { when looping(2) { } }"));
        assertThrows(RuntimeException.class, () -> parse("supervise R { }"));
        assertThrows(RuntimeException.class, () -> parse("supervise R { looping(2) { wrap_up } }"));
    }

    @Test
    void superviseStaysUsableAsAName() {
        LoomScript s = parse("agent A { model: \"m\" } workflow W() { delegate \"go\" to A -> supervise }");
        assertTrue(s.getSupervisions().isEmpty());
    }

    // ---- checking ----

    private static List<ScriptValidator.Problem> problems(String source) {
        return new ScriptValidator().validate(parse(source), new ScriptValidator.Context().registeredTools(java.util.Set.of("Search")));
    }

    @Test
    void theAgentAndTheToolMustExist() {
        var noAgent = problems("supervise Ghost { when looping(2) { wrap_up } }");
        assertTrue(noAgent.stream().anyMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && p.message().contains("no agent named Ghost")), noAgent.toString());
        var noTool = problems("agent R { model: \"m\" tools: [Search] }\nsupervise R { when tool_failing(Fetch, 2) { wrap_up } }");
        assertTrue(noTool.stream().anyMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && p.message().contains("no tool named Fetch") && p.message().contains("Search")), noTool.toString());
        var twice = problems("agent R { model: \"m\" }\nsupervise R { when looping(2) { wrap_up } }\nsupervise R { when steps > 3 { wrap_up } }");
        assertTrue(twice.stream().anyMatch(p -> p.message().contains("supervised by two blocks")), twice.toString());
        var fine = problems("agent R { model: \"m\" tools: [Search] }\nsupervise R { when tool_failing(Search, 2) { wrap_up } }\nworkflow W() { delegate \"x\" to R -> out_text\n note \"{out_text}\" }");
        assertTrue(fine.stream().noneMatch(p -> p.severity() == ScriptValidator.Severity.ERROR), fine.toString());
    }

    // ---- the signals, counted ----

    /** A watcher that only records what its rules did. */
    private static final class Recorder implements Supervision.Actor {
        final List<String> did = new ArrayList<>();
        public void steer(String stepId, String agent, String text, String rule) { did.add("steer " + text + " <- " + rule); }
        public void wrapUp(String stepId, String agent, String rule) { did.add("wrap_up <- " + rule); }
        public void cancel(String stepId, String agent, StopOutcome o, String rule) { did.add("cancel " + o + " <- " + rule); }
    }

    private static Supervision.Run run(String rules, Recorder rec) {
        return new Supervision.Run(parse("agent R { model: \"m\" tools: [Search] }\nsupervise R {\n" + rules + "\n}").getSupervisions().get(0), "W/s0", rec);
    }

    private static io.github.llm4j.telemetry.ToolCallEvent tool(String name, boolean ok) {
        return new io.github.llm4j.telemetry.ToolCallEvent(1, name, java.time.Instant.EPOCH, 1, 0,
                ok ? io.github.llm4j.telemetry.ToolOutcome.EXECUTED : io.github.llm4j.telemetry.ToolOutcome.UNKNOWN_TOOL, false, null, null, 1, 1);
    }

    @Test
    void loopingCountsTheSameActionInARow() {
        Recorder rec = new Recorder();
        Supervision.Run r = run("when looping(3) { steer \"change course\" }", rec);
        r.action("Search", "a"); r.action("Search", "a");
        assertTrue(rec.did.isEmpty(), "two is not three");
        r.action("Search", "a");
        assertEquals(List.of("steer change course <- looping(3)"), rec.did);
        r.action("Search", "a");
        assertEquals(1, rec.did.size(), "it fires when the condition becomes true, not on every action after");
        r.action("Search", "b"); r.action("Search", "b"); r.action("Search", "b");
        assertEquals(2, rec.did.size(), "after the loop broke and began again it fires again");
    }

    @Test
    void onceMeansOncePerStep() {
        Recorder rec = new Recorder();
        Supervision.Run r = run("when looping(2) { steer \"x\" once }", rec);
        r.action("S", "a"); r.action("S", "a");
        r.action("S", "b"); r.action("S", "b"); r.action("S", "b");
        assertEquals(1, rec.did.size());
    }

    @Test
    void aDifferentInputOrToolIsNotALoop() {
        Recorder rec = new Recorder();
        Supervision.Run r = run("when looping(2) { wrap_up }", rec);
        r.action("S", "a"); r.action("S", "b"); r.action("T", "b");
        assertTrue(rec.did.isEmpty());
    }

    @Test
    void aToolFailingCountsFailuresInARowAndASuccessStartsAgain() {
        Recorder rec = new Recorder();
        Supervision.Run r = run("when tool_failing(Search, 3) { wrap_up }", rec);
        r.toolCall(tool("Search", false)); r.toolCall(tool("Search", false)); r.toolCall(tool("Search", true)); r.toolCall(tool("Search", false)); r.toolCall(tool("Search", false));
        assertTrue(rec.did.isEmpty());
        r.toolCall(tool("Other", false));
        assertTrue(rec.did.isEmpty(), "another tool's failure is not Search's");
        r.toolCall(tool("Search", false));
        assertEquals(List.of("wrap_up <- tool_failing(Search, 3)"), rec.did);
    }

    @Test
    void stepsAndContextAreWatchedToo() {
        Recorder rec = new Recorder();
        Supervision.Run r = run("when steps > 2 { cancel skip }\nwhen context > 90% { wrap_up }", rec);
        r.modelCall(); r.modelCall();
        assertTrue(rec.did.isEmpty());
        r.modelCall();
        assertEquals(List.of("cancel SKIP <- steps > 2"), rec.did);
        r.context(new io.github.llm4j.context.ContextUsage(80, new io.github.llm4j.context.ContextWindow(100, 0), io.github.llm4j.context.ContextUsage.Source.ESTIMATED));
        assertEquals(1, rec.did.size());
        r.context(new io.github.llm4j.context.ContextUsage(95, new io.github.llm4j.context.ContextWindow(100, 0), io.github.llm4j.context.ContextUsage.Source.ESTIMATED));
        assertEquals("wrap_up <- context > 90%", rec.did.get(1));
    }

    // ---- a rule acts on a real run ----

    private static final String SCRIPT = """
            agent Looper { model: "test" system: "You are Looper." tools: [calculator] max_iterations: 20 }
            supervise Looper {
                when looping(3) { steer "You are repeating yourself. Try something else." once }
            }
            workflow Main() { delegate "go" to Looper -> out_text }
            """;

    private static String action(String thought) {
        return "```json\n{\"thought\": \"" + thought + "\", \"action\": \"calculator\", \"action_input\": {\"expression\": \"1+1\"}}\n```";
    }

    @Test
    void aLoopingAgentIsSteeredByItsRuleAndTheSteerIsJournaledAsSupervision() {
        List<String> seen = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        LLMClientFactory models = name -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String all = request.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
                seen.add(all);
                int n = calls.incrementAndGet();
                String body = all.contains("repeating yourself") ? "```json\n{\"thought\": \"ok\", \"final_answer\": \"changed course\"}\n```" : action("again");
                return LLMResponse.builder().content(n > 12 ? "```json\n{\"thought\": \"give up\", \"final_answer\": \"gave up\"}\n```" : body).model("m").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        HarnessExecutor ex = new HarnessExecutor(parse(SCRIPT), new ToolRegistry(), models);
        RunJournal journal = RunJournal.inMemory();
        ex.setJournal(journal);
        List<TraceEvent> events = new CopyOnWriteArrayList<>();
        ex.addTraceListener(events::add);
        ex.initialize();
        ex.executeWorkflow("Main", new HashMap<>());

        assertEquals("changed course", ex.getContext().getVariable("out_text"));
        assertTrue(journal.all().values().stream().anyMatch(e -> "steer".equals(e.kind()) && "supervision".equals(((Map<?, ?>) e.value()).get("origin"))
                && String.valueOf(((Map<?, ?>) e.value()).get("text")).contains("repeating yourself")));
        assertTrue(events.stream().anyMatch(e -> "supervision".equals(e.data().get("action")) && "looping(3)".equals(e.data().get("rule")) && "steer".equals(e.data().get("does"))), events.toString());
    }

    @Test
    void aWrapUpRuleEndsTheStepWithTheLastAnswerAndAnAgentNeedNotBeSteerableToBeSteeredByIt() {
        String script = SCRIPT.replace("steer \"You are repeating yourself. Try something else.\" once", "wrap_up");
        AtomicInteger calls = new AtomicInteger();
        LLMClientFactory models = name -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String all = request.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
                calls.incrementAndGet();
                String body = all.contains("Stop using tools now") ? "```json\n{\"thought\": \"wrap\", \"final_answer\": \"wrapped by rule\"}\n```" : action("again");
                return LLMResponse.builder().content(body).model("m").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        HarnessExecutor ex = new HarnessExecutor(parse(script), new ToolRegistry(), models);
        RunJournal journal = RunJournal.inMemory();
        ex.setJournal(journal);
        ex.initialize();
        ex.executeWorkflow("Main", new HashMap<>());
        assertEquals("wrapped by rule", ex.getContext().getVariable("out_text"));
        assertTrue(journal.all().values().stream().anyMatch(e -> "stop".equals(e.kind()) && "supervision".equals(((Map<?, ?>) e.value()).get("reason"))
                && "WRAP_UP".equals(((Map<?, ?>) e.value()).get("kind"))));
    }
}
