package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.StopReport;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.InterventionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.OutcomeStmt;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code on_stopped { }} and {@code intervention { }}: how they parse, how they decide, and that a
 * run obeys them.
 */
class StopHandlerTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static DelegateStmt firstDelegate(LoomScript s) {
        return (DelegateStmt) s.getWorkflows().get(0).getStatements().get(0);
    }

    private static StopReport stop(String reason, boolean changed) {
        return new StopReport(
                StopReport.Kind.CANCEL,
                reason,
                "alice",
                StopReport.Phase.BETWEEN_TOOLS,
                "partial",
                List.of(),
                changed);
    }

    // ---- parsing ----

    @Test
    void aHandlerMayHoldOutcomesAndConditions() {
        DelegateStmt d =
                firstDelegate(
                        parse(
                                """
                agent A { model: "m" }
                workflow W() {
                    delegate "go" to A -> out
                        timeout 90s
                        on_stopped {
                            alt (_stop.reason == "timeout") {
                                alt (_stop.changed == "no") { retry } else { use partial }
                            }
                            alt (_stop.reason == "user") { skip }
                        }
                }
                """));
        assertTrue(d.hasOnStopped());
        assertEquals(2, d.getOnStopped().size());
        AltStmt outer = (AltStmt) d.getOnStopped().get(0);
        AltStmt inner = (AltStmt) outer.getIfBranch().get(0);
        assertEquals(OutcomeStmt.Kind.RETRY, ((OutcomeStmt) inner.getIfBranch().get(0)).getKind());
        assertEquals(
                OutcomeStmt.Kind.USE_PARTIAL,
                ((OutcomeStmt) inner.getElseBranch().get(0)).getKind());
    }

    @Test
    void onFailureAndOnStoppedMayComeInEitherOrder() {
        for (String order :
                List.of(
                        "on_failure { note \"f\" } on_stopped { skip }",
                        "on_stopped { skip } on_failure { note \"f\" }")) {
            DelegateStmt d =
                    firstDelegate(
                            parse(
                                    "agent A { model: \"m\" } workflow W() { delegate \"go\" to A -> out "
                                            + order
                                            + " }"));
            assertEquals(1, d.getOnFailure().size());
            assertEquals(1, d.getOnStopped().size());
        }
    }

    @Test
    void outcomeWordsAreOnlyOutcomesInsideAHandler() {
        LoomScript s =
                parse("agent A { model: \"m\" } workflow W() { delegate \"go\" to A -> skip }");
        assertEquals(
                "skip", firstDelegate(s).getVariableName(), "skip is still a fine variable name");
        assertThrows(
                RuntimeException.class,
                () -> parse("agent A { model: \"m\" } workflow W() { skip }"));
    }

    @Test
    void aStepHasOneHandler() {
        RuntimeException e =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                parse(
                                        "agent A { model: \"m\" } workflow W() { delegate \"go\" to A -> out on_stopped { skip } on_stopped { skip } }"));
        assertTrue(e.getMessage().contains("only one on_stopped"), e.getMessage());
    }

    @Test
    void anInterventionBlockSetsDefaultsByReason() {
        InterventionDef def =
                parse(
                                """
                intervention { timeout: retry  user: ask  sibling: discard  shutdown: suspend  budget: use partial }
                agent A { model: "m" }
                """)
                        .getIntervention();
        assertEquals(OutcomeStmt.Kind.RETRY, def.getByReason().get("timeout"));
        assertEquals(OutcomeStmt.Kind.ASK, def.getByReason().get("user"));
        assertEquals(OutcomeStmt.Kind.DISCARD, def.getByReason().get("sibling"));
        assertEquals(OutcomeStmt.Kind.SUSPEND, def.getByReason().get("shutdown"));
        assertEquals(OutcomeStmt.Kind.USE_PARTIAL, def.getByReason().get("budget"));
    }

    @Test
    void anInterventionBlockRefusesWhatItCannotDo() {
        String unknown =
                assertThrows(RuntimeException.class, () -> parse("intervention { whim: skip }"))
                        .getMessage();
        assertTrue(unknown.contains("is not a reason") && unknown.contains("timeout"), unknown);
        String wrapUp =
                assertThrows(
                                RuntimeException.class,
                                () -> parse("intervention { timeout: wrap_up }"))
                        .getMessage();
        assertTrue(wrapUp.contains("wrap_up is not an outcome"), wrapUp);
        assertThrows(
                RuntimeException.class,
                () -> parse("intervention { timeout: retry timeout: skip }"));
        assertThrows(
                RuntimeException.class,
                () -> parse("intervention { timeout: retry } intervention { user: ask }"));
        assertThrows(RuntimeException.class, () -> parse("intervention { timeout: }"));
    }

    @Test
    void interventionStaysUsableAsAName() {
        LoomScript s =
                parse(
                        "agent A { model: \"m\" } workflow W() { delegate \"go\" to A -> intervention }");
        assertNull(s.getIntervention());
        assertEquals("intervention", firstDelegate(s).getVariableName());
    }

    // ---- deciding ----

    @Test
    void aReasonThatIsNotOneOfTheKnownOnesCountsAsTheUser() {
        assertEquals("user", StopHandlers.category(stop("the input was wrong", false)));
        assertEquals("user", StopHandlers.category(stop(null, false)));
        assertEquals("timeout", StopHandlers.category(stop("Timeout", false)));
        assertEquals("budget", StopHandlers.category(stop("budget", false)));
    }

    private static List<io.github.llm4j.loom.ast.Statement> handler(String body) {
        return firstDelegate(
                        parse(
                                "agent A { model: \"m\" } workflow W() { delegate \"go\" to A -> out on_stopped { "
                                        + body
                                        + " } }"))
                .getOnStopped();
    }

    @Test
    void theHandlerReadsTheStop() {
        var h =
                handler(
                        "alt (_stop.reason == \"timeout\") { alt (_stop.changed == \"no\") { retry } else { use partial } } alt (_stop.reason == \"user\") { skip }");
        assertEquals(
                Optional.of(StopOutcome.RETRY),
                StopHandlers.declared(h, null, stop("timeout", false)));
        assertEquals(
                Optional.of(StopOutcome.USE_PARTIAL),
                StopHandlers.declared(h, null, stop("timeout", true)));
        assertEquals(
                Optional.of(StopOutcome.SKIP),
                StopHandlers.declared(h, null, stop("no longer needed", false)));
        assertEquals(
                Optional.empty(),
                StopHandlers.declared(h, null, stop("budget", false)),
                "nothing matches: the defaults apply");
    }

    @Test
    void theHandlerCanReadTheNoteAndThePartialAnswer() {
        var h =
                handler(
                        "alt (_stop.note == \"stale\") { discard } alt (_stop.partial == \"partial\") { conclude }");
        assertEquals(
                Optional.of(StopOutcome.DISCARD),
                StopHandlers.declared(h, null, stop("stale", false)));
        assertEquals(
                Optional.of(StopOutcome.CONCLUDE),
                StopHandlers.declared(h, null, stop("other", false)));
    }

    @Test
    void theHandlerWinsOverTheScriptDefaultsWhichWinOverNothing() {
        InterventionDef def = parse("intervention { user: ask timeout: skip }").getIntervention();
        var h = handler("alt (_stop.reason == \"user\") { use partial }");
        assertEquals(
                Optional.of(StopOutcome.USE_PARTIAL),
                StopHandlers.declared(h, def, stop("x", false)));
        assertEquals(
                Optional.of(StopOutcome.SKIP),
                StopHandlers.declared(h, def, stop("timeout", false)));
        assertEquals(
                Optional.of(StopOutcome.ASK),
                StopHandlers.declared(List.of(), def, stop("x", false)));
        assertEquals(Optional.empty(), StopHandlers.declared(List.of(), null, stop("x", false)));
    }

    // ---- a run obeys ----

    private static final String SCRIPT =
            """
            %s
            agent Looper { model: "test" system: "You are Looper." tools: [MockTool] max_iterations: 50 }
            agent Closer { model: "test" system: "You are Closer." }
            workflow Main() {
                delegate "go" to Looper -> out_text %s
                delegate "after" to Closer -> done_text
            }
            """;

    private static InterventionRuntimeTest.Run start(
            String top,
            String stepSettings,
            InterventionRuntimeTest.Model model,
            RunJournal journal)
            throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        HarnessExecutor ex =
                new HarnessExecutor(
                        parse(SCRIPT.formatted(top, stepSettings)), tools, model.factory());
        ex.setJournal(journal);
        ex.initialize();
        return new InterventionRuntimeTest.Run(ex).start(model);
    }

    @Test
    void aHandlerThatSaysSkipLetsTheRunCarryOnWithoutAskingAnyone() throws Exception {
        InterventionRuntimeTest.Model model = new InterventionRuntimeTest.Model(false);
        RunJournal journal = RunJournal.inMemory();
        var run =
                start("", "on_stopped { alt (_stop.reason == \"user\") { skip } }", model, journal);
        run.executor.cancel("Looper", "not wanted", "alice", null);
        run.finish(model);
        assertNull(run.error.get());
        assertEquals("done", run.executor.getContext().getVariable("done_text"));
        assertTrue(journal.all().values().stream().anyMatch(e -> "skipped".equals(e.kind())));
        assertTrue(
                journal.all().values().stream()
                        .anyMatch(
                                e ->
                                        "stop".equals(e.kind())
                                                && String.valueOf(
                                                                ((Map<?, ?>) e.value()).get("why"))
                                                        .contains("declared by the script")));
    }

    @Test
    void theScriptDefaultAppliesToAStepWithNoHandler() throws Exception {
        InterventionRuntimeTest.Model model = new InterventionRuntimeTest.Model(false);
        var run = start("intervention { user: use partial }", "", model, RunJournal.inMemory());
        run.executor.cancel("Looper", "enough", "alice", null);
        run.finish(model);
        assertNull(run.error.get());
        assertEquals("look", run.executor.getContext().getVariable("out_text"));
        assertEquals("done", run.executor.getContext().getVariable("done_text"));
    }

    @Test
    void aPersonWhoNamesAnOutcomeOverridesTheScript() throws Exception {
        InterventionRuntimeTest.Model model = new InterventionRuntimeTest.Model(false);
        var run = start("intervention { user: use partial }", "", model, RunJournal.inMemory());
        run.executor.cancel("Looper", "not needed", "alice", StopOutcome.SKIP);
        run.finish(model);
        assertNull(run.error.get());
        assertEquals("", run.executor.getContext().getVariable("out_text"));
    }

    @Test
    void aHandlerThatSaysAskWithNobodyToAskSuspendsTheRun() throws Exception {
        InterventionRuntimeTest.Model model = new InterventionRuntimeTest.Model(false);
        var run = start("", "on_stopped { ask }", model, RunJournal.inMemory());
        run.executor.cancel("Looper", "hmm", "alice", null);
        run.finish(model);
        assertInstanceOf(io.github.llm4j.loom.runtime.RunSuspended.class, run.error.get());
    }

    // ---- checking ----

    private static List<ScriptValidator.Problem> problems(String source) {
        return new ScriptValidator()
                .validate(
                        parse(source),
                        new ScriptValidator.Context().registeredTools(java.util.Set.of("T")));
    }

    @Test
    void aMisspeltStopFieldIsAnError() {
        var found =
                problems(
                        "agent A { model: \"m\" } workflow W() { delegate \"go\" to A -> out on_stopped { alt (_stop.reasson == \"user\") { skip } } }");
        assertTrue(
                found.stream()
                        .anyMatch(
                                p ->
                                        p.severity() == ScriptValidator.Severity.ERROR
                                                && p.message().contains("_stop.reasson")
                                                && p.message().contains("changed")),
                found.toString());
    }

    @Test
    void aRetryForAnAgentWithToolsShouldTestWhetherAnythingChanged() {
        String agent =
                "agent A { model: \"m\" tools: [T] streaming: true steerable: true } workflow W() { delegate \"go\" to A -> out on_stopped { %s } }";
        assertTrue(
                problems(agent.formatted("retry")).stream()
                        .anyMatch(
                                p ->
                                        p.severity() == ScriptValidator.Severity.WARNING
                                                && p.message().contains("_stop.changed")));
        assertTrue(
                problems(agent.formatted("alt (_stop.changed == \"no\") { retry }")).stream()
                        .noneMatch(p -> p.message().contains("_stop.changed")));
        assertTrue(
                problems(
                                "agent A { model: \"m\" } workflow W() { delegate \"go\" to A -> out on_stopped { retry } }")
                        .stream()
                        .noneMatch(p -> p.message().contains("_stop.changed")));
    }

    // ---- rewinding from a handler ----

    private static final String REPORT =
            """
            agent Collector { model: "test" system: "You are Collector." }
            agent Looper { model: "test" system: "You are Looper." tools: [MockTool] max_iterations: 50 }
            workflow Main() {
                checkpoint collected starting with feedback = "none"
                delegate "collect. Notes: {feedback}" to Collector -> data
                delegate "write from {data}" to Looper -> out_text
                    on_stopped {
                        alt (_stop.reason == "user") {
                            rewind to collected at most 2 times carrying feedback = "{_stop.note}" side effects: keep
                        }
                    }
            }
            """;

    @Test
    void aHandlerMaySendTheRunBackToADeclaredCheckpointCarryingTheReason() throws Exception {
        InterventionRuntimeTest.Model model = new InterventionRuntimeTest.Model(false);
        RunJournal journal = RunJournal.inMemory();
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        java.util.List<String> collectorBriefings =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        io.github.llm4j.loom.execution.LLMClientFactory inner = model.factory();
        HarnessExecutor ex =
                new HarnessExecutor(
                        parse(REPORT),
                        tools,
                        name -> {
                            io.github.llm4j.LLMClient real = inner.createClient(name);
                            return new io.github.llm4j.LLMClient() {
                                @Override
                                public io.github.llm4j.model.LLMResponse chat(
                                        io.github.llm4j.model.LLMRequest r) {
                                    String first = r.getMessages().get(0).getContent();
                                    if (first.contains("You are Collector"))
                                        collectorBriefings.add(r.getMessages().get(1).getContent());
                                    return real.chat(r);
                                }

                                @Override
                                public java.util.stream.Stream<io.github.llm4j.model.LLMResponse>
                                        chatStream(io.github.llm4j.model.LLMRequest r) {
                                    return real.chatStream(r);
                                }
                            };
                        });
        ex.setJournal(journal);
        ex.initialize();
        model.blockFirstCall = true;
        var run = new InterventionRuntimeTest.Run(ex);
        run.thread.start();
        // the Looper is the one that blocks; wait for it, cancel it with a reason, let it go
        assertTrue(model.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        model.finishNow = true;
        assertEquals(1, ex.cancel("Looper", "the sources were stale", "alice", null));
        run.finish(model);

        assertNull(run.error.get());
        assertEquals(2, collectorBriefings.size(), "the collector ran again");
        assertTrue(collectorBriefings.get(0).contains("Notes: none"), collectorBriefings.get(0));
        assertTrue(
                collectorBriefings.get(1).contains("Notes: the sources were stale"),
                collectorBriefings.get(1));
        assertEquals(
                "done",
                ex.getContext().getVariable("out_text"),
                "the writer ran again and finished");
    }

    @Test
    void aRewindCannotBeNamedByWhoeverCancels() {
        HarnessExecutor ex =
                new HarnessExecutor(
                        parse(REPORT),
                        new ToolRegistry(),
                        new InterventionRuntimeTest.Model(false).factory());
        assertThrows(
                IllegalArgumentException.class,
                () -> ex.cancel("Looper", "x", "alice", StopOutcome.REWIND));
    }

    @Test
    void aRewindInAHandlerMustNameACheckpointTheScriptDeclared() {
        var found =
                problems(
                        """
                agent A { model: "m" }
                workflow W() {
                    delegate "go" to A -> out on_stopped { rewind to nowhere at most 1 times }
                }
                """);
        assertTrue(
                found.stream()
                        .anyMatch(
                                p ->
                                        p.severity() == ScriptValidator.Severity.ERROR
                                                && p.message()
                                                        .contains("no checkpoint named nowhere")),
                found.toString());
        var later =
                problems(
                        """
                agent A { model: "m" }
                workflow W() {
                    delegate "go" to A -> out on_stopped { rewind to later at most 1 times }
                    checkpoint later
                }
                """);
        assertTrue(
                later.stream()
                        .anyMatch(
                                p ->
                                        p.severity() == ScriptValidator.Severity.ERROR
                                                && p.message().contains("must come earlier")),
                later.toString());
        assertTrue(
                problems(
                                """
                agent A { model: "m" }
                workflow W() {
                    checkpoint c
                    delegate "go" to A -> out on_stopped { rewind to c at most 1 times }
                }
                """)
                        .stream()
                        .noneMatch(p -> p.severity() == ScriptValidator.Severity.ERROR));
    }

    // ---- a person sends the run back ----

    private HarnessExecutor reportRun(
            String script,
            java.util.List<String> collectorBriefings,
            InterventionRuntimeTest.Model model,
            RunJournal journal) {
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        LLMClientFactory inner = model.factory();
        HarnessExecutor ex =
                new HarnessExecutor(
                        parse(script),
                        tools,
                        name -> {
                            io.github.llm4j.LLMClient real = inner.createClient(name);
                            return new io.github.llm4j.LLMClient() {
                                @Override
                                public io.github.llm4j.model.LLMResponse chat(
                                        io.github.llm4j.model.LLMRequest r) {
                                    if (r.getMessages()
                                            .get(0)
                                            .getContent()
                                            .contains("You are Collector"))
                                        collectorBriefings.add(r.getMessages().get(1).getContent());
                                    return real.chat(r);
                                }

                                @Override
                                public java.util.stream.Stream<io.github.llm4j.model.LLMResponse>
                                        chatStream(io.github.llm4j.model.LLMRequest r) {
                                    return real.chatStream(r);
                                }
                            };
                        });
        ex.setJournal(journal);
        ex.initialize();
        return ex;
    }

    @Test
    void aPersonMaySendTheRunBackToADeclaredCheckpointWithTheirOwnWords() throws Exception {
        InterventionRuntimeTest.Model model = new InterventionRuntimeTest.Model(false);
        java.util.List<String> collector = new java.util.concurrent.CopyOnWriteArrayList<>();
        HarnessExecutor ex =
                reportRun(
                        REPORT.replace(
                                "alt (_stop.reason == \"user\") {",
                                "alt (_stop.reason == \"budget\") {"),
                        collector,
                        model,
                        RunJournal.inMemory());
        var run = new InterventionRuntimeTest.Run(ex).start(model);
        model.finishNow = true;
        assertEquals(1, ex.cancelBack("Looper", "collected", "the sources were stale", "alice"));
        run.finish(model);

        assertNull(run.error.get());
        assertEquals(2, collector.size(), "the collector ran again");
        assertTrue(collector.get(1).contains("Notes: the sources were stale"), collector.get(1));
        assertEquals("done", ex.getContext().getVariable("out_text"));
    }

    @Test
    void aPersonCannotSendTheRunBackToACheckpointTheScriptDoesNotDeclare() throws Exception {
        InterventionRuntimeTest.Model model = new InterventionRuntimeTest.Model(false);
        RunJournal journal = RunJournal.inMemory();
        HarnessExecutor ex =
                reportRun(
                        REPORT, new java.util.concurrent.CopyOnWriteArrayList<>(), model, journal);
        var events = new java.util.concurrent.CopyOnWriteArrayList<TraceEvent>();
        ex.addTraceListener(events::add);
        var run = new InterventionRuntimeTest.Run(ex).start(model);
        assertEquals(0, ex.cancelBack("Looper", "nowhere", "x", "alice"));
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        "cancel_back_refused".equals(e.data().get("action"))
                                                && String.valueOf(e.data().get("why"))
                                                        .contains("declares: start, collected")),
                events.toString());
        model.finishNow = true;
        run.finish(model);
        assertNull(run.error.get());
    }

    @Test
    void aStepWithNoRewindOfItsOwnCannotBeSentBack() throws Exception {
        InterventionRuntimeTest.Model model = new InterventionRuntimeTest.Model(false);
        HarnessExecutor ex =
                reportRun(
                        REPORT.replace(
                                "rewind to collected at most 2 times carrying feedback = \"{_stop.note}\" side effects: keep",
                                "skip"),
                        new java.util.concurrent.CopyOnWriteArrayList<>(),
                        model,
                        RunJournal.inMemory());
        var events = new java.util.concurrent.CopyOnWriteArrayList<TraceEvent>();
        ex.addTraceListener(events::add);
        var run = new InterventionRuntimeTest.Run(ex).start(model);
        assertEquals(0, ex.cancelBack("Looper", "collected", "x", "alice"));
        assertTrue(
                events.stream()
                        .anyMatch(
                                e -> String.valueOf(e.data().get("why")).contains("no on_stopped")),
                events.toString());
        model.finishNow = true;
        run.finish(model);
    }
}
