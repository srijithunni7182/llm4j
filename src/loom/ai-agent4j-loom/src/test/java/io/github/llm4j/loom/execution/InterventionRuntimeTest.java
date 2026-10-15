package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Steering, wrapping up and cancelling a running agent through the executor, and what the run does about it. */
class InterventionRuntimeTest {

    private static final String JSON_ACTION_MOCK = "```json\n{\"thought\": \"look\", \"action\": \"MockTool\", \"action_input\": {\"test\": \"x\"}}\n```";
    private static final String JSON_ACTION_READ = "```json\n{\"thought\": \"look\", \"action\": \"calculator\", \"action_input\": {\"expression\": \"1+1\"}}\n```";
    private static final String DONE = "```json\n{\"thought\": \"ok\", \"final_answer\": \"done\"}\n```";
    private static final String WRAPPED = "```json\n{\"thought\": \"enough\", \"final_answer\": \"wrapped answer\"}\n```";

    /** A model whose first Looper call waits for the test, then keeps asking for a tool until told to finish. */
    static final class Model {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> briefings = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean read;
        volatile boolean blockFirstCall = true;
        volatile boolean finishNow;

        Model(boolean read) {
            this.read = read;
        }

        LLMClientFactory factory() {
            return name -> new LLMClient() {
                @Override
                public LLMResponse chat(LLMRequest request) {
                    String all = request.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
                    String agent = request.getMessages().get(0).getContent().replaceFirst("(?s)^You are (\\w+)\\..*", "$1");
                    if (!agent.equals("Looper")) return LLMResponse.builder().content(DONE).model(name).build();
                    if (all.contains("Stop using tools now")) return LLMResponse.builder().content(WRAPPED).model(name).build();
                    briefings.add(request.getMessages().get(1).getContent());
                    int n = calls.incrementAndGet();
                    try {
                        if (blockFirstCall && n == 1) {
                            entered.countDown();
                            release.await(10, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    String action = read ? JSON_ACTION_READ : JSON_ACTION_MOCK;
                    return LLMResponse.builder().content(finishNow && n > 1 ? DONE : action).model(name).build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest request) {
                    return Stream.empty();
                }
            };
        }
    }

    /** A run on its own thread. */
    static final class Run {
        final HarnessExecutor executor;
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Thread thread;

        Run(HarnessExecutor executor) {
            this.executor = executor;
            this.thread = new Thread(() -> {
                try {
                    executor.executeWorkflow("Main", Map.of());
                } catch (Throwable t) {
                    error.set(t);
                }
            });
        }

        Run start(Model model) throws InterruptedException {
            thread.start();
            assertTrue(model.entered.await(5, TimeUnit.SECONDS), "the agent started");
            return this;
        }

        void finish(Model model) throws InterruptedException {
            model.release.countDown();
            thread.join(10_000);
            assertFalse(thread.isAlive(), "the run ended");
        }
    }

    private static final String SCRIPT = """
            agent Looper { model: "test" system: "You are Looper." tools: [%s] max_iterations: 50 steerable: true }
            agent Closer { model: "test" system: "You are Closer." }
            workflow Main() {
                delegate "go" to Looper -> out_text %s
                delegate "after" to Closer -> done_text
            }
            """;

    private static HarnessExecutor executor(String tool, String stepSettings, Model model, RunJournal journal, HumanInterface human) {
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        LoomScript parsed = new LoomParser(new Lexer(SCRIPT.formatted(tool, stepSettings)).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, tools, model.factory());
        if (journal != null) executor.setJournal(journal);
        if (human != null) executor.setHumanInterface(human);
        executor.initialize();
        return executor;
    }

    private static List<String> traceTypes(HarnessExecutor executor) {
        List<String> types = new CopyOnWriteArrayList<>();
        executor.addTraceListener(e -> types.add(e.type() + ":" + e.data().getOrDefault("action", "") + ":" + e.data().getOrDefault("outcome", "")));
        return types;
    }

    @Test
    void aCancelIsPutToThePersonWhoCanKeepWhatTheAgentHad() throws Exception {
        Model model = new Model(false);
        RunJournal journal = RunJournal.inMemory();
        List<String> asked = new CopyOnWriteArrayList<>();
        HumanInterface person = new HumanInterface() {
            @Override public String promptHuman(String message) { asked.add(message); return "use partial"; }
        };
        HarnessExecutor ex = executor("MockTool", "", model, journal, person);
        List<String> events = traceTypes(ex);
        Run run = new Run(ex).start(model);

        assertEquals(1, ex.cancel("Looper", "the input was wrong", "alice", null));
        run.finish(model);

        assertNull(run.error.get());
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).contains("the input was wrong") && asked.get(0).contains("alice") && asked.get(0).contains("It may already have changed"), asked.get(0));
        assertEquals("look", ex.getContext().getVariable("out_text"), "the agent's last note stands as its answer");
        assertEquals("done", ex.getContext().getVariable("done_text"));
        assertTrue(journal.all().values().stream().anyMatch(e -> "stop".equals(e.kind())
                && ((Map<?, ?>) e.value()).get("outcome").equals("USE_PARTIAL") && ((Map<?, ?>) e.value()).get("reason").equals("the input was wrong")));
        assertTrue(events.contains("intervention:cancel:"), events.toString());
        assertTrue(events.stream().anyMatch(t -> t.startsWith("step_stopped:")), events.toString());
    }

    @Test
    void withNobodyToAskTheRunSuspendsAndTheStepRestartsWithWhatThePersonSaid() throws Exception {
        Model first = new Model(false);
        RunJournal journal = RunJournal.inMemory();
        HarnessExecutor ex = executor("MockTool", "", first, journal, null);
        Run run = new Run(ex).start(first);

        assertEquals(1, ex.steer("Looper", "use the EU figures", "alice"));
        assertEquals(1, ex.cancel("Looper", "user", "alice", null));
        run.finish(first);

        assertInstanceOf(RunSuspended.class, run.error.get());
        assertEquals(RunSuspended.Reason.OPERATOR, ((RunSuspended) run.error.get()).reason());
        assertTrue(journal.all().entrySet().stream().noneMatch(e -> "delegate".equals(e.getValue().kind())), "nothing was recorded as the step's result");
        assertTrue(journal.get("s0#steer.1").isPresent() || journal.all().keySet().stream().anyMatch(k -> k.contains("#steer.1")), "the steer is on the journal");

        // Later, on another server: the same journal, a model that now finishes. The stopped step restarts, and is told what the person said.
        Model second = new Model(false);
        second.blockFirstCall = false;
        second.finishNow = true;
        HarnessExecutor resumed = executor("MockTool", "", second, journal, null);
        resumed.executeWorkflow("Main", Map.of());

        assertEquals("done", resumed.getContext().getVariable("out_text"));
        assertTrue(second.briefings.get(0).contains("has told you") && second.briefings.get(0).contains("use the EU figures"), second.briefings.get(0));
    }

    @Test
    void aWrapUpNeedsNobodyToAskAndTheLastCallBecomesTheAnswer() throws Exception {
        Model model = new Model(false);
        RunJournal journal = RunJournal.inMemory();
        HarnessExecutor ex = executor("MockTool", "", model, journal, null);
        Run run = new Run(ex).start(model);

        assertEquals(1, ex.wrapUp("Looper", "the deadline is near", "scheduler"));
        run.finish(model);

        assertNull(run.error.get());
        assertEquals("wrapped answer", ex.getContext().getVariable("out_text"));
        assertEquals("done", ex.getContext().getVariable("done_text"));
        assertTrue(journal.all().values().stream().anyMatch(e -> "stop".equals(e.kind()) && "WRAP_UP".equals(((Map<?, ?>) e.value()).get("kind"))
                && "USE_PARTIAL".equals(((Map<?, ?>) e.value()).get("outcome"))));
    }

    @Test
    void whoeverCancelsMayNameWhatTheRunDoesNext() throws Exception {
        // skip: the step's on_failure block runs, the run carries on
        Model m1 = new Model(false);
        RunJournal j1 = RunJournal.inMemory();
        HarnessExecutor skip = executor("MockTool", "on_failure { note \"skipped it\" }", m1, j1, null);
        Run r1 = new Run(skip).start(m1);
        skip.cancel("Looper", "not needed", "alice", StopOutcome.SKIP);
        r1.finish(m1);
        assertNull(r1.error.get());
        assertEquals("", skip.getContext().getVariable("out_text"));
        assertEquals("done", skip.getContext().getVariable("done_text"));
        assertTrue(j1.all().values().stream().anyMatch(e -> "skipped".equals(e.kind()) && "not needed".equals(e.value())));

        // discard: nothing runs for the step, not even its on_failure
        Model m2 = new Model(false);
        RunJournal j2 = RunJournal.inMemory();
        HarnessExecutor discard = executor("MockTool", "on_failure { delegate \"handler\" to Closer -> handled_text }", m2, j2, null);
        Run r2 = new Run(discard).start(m2);
        discard.cancel("Looper", "sibling won", "race", StopOutcome.DISCARD);
        r2.finish(m2);
        assertNull(r2.error.get());
        assertEquals("", discard.getContext().getVariable("handled_text"));
        assertTrue(j2.all().values().stream().anyMatch(e -> "discarded".equals(e.kind())));

        // conclude: the run ends
        Model m3 = new Model(false);
        HarnessExecutor conclude = executor("MockTool", "", m3, RunJournal.inMemory(), null);
        Run r3 = new Run(conclude).start(m3);
        conclude.cancel("Looper", "enough", "alice", StopOutcome.CONCLUDE);
        r3.finish(m3);
        assertInstanceOf(RunStopped.class, r3.error.get());
        assertEquals("", conclude.getContext().getVariable("done_text"));
    }

    @Test
    void aRetryRunsTheStepAgainWhenItOnlyReadAndIsRefusedWhenItMayHaveChangedThings() throws Exception {
        // an agent that only used a read-only tool: a named retry runs the step again
        Model reader = new Model(true);
        HarnessExecutor ex = executor("calculator", "", reader, RunJournal.inMemory(), null);
        Run run = new Run(ex).start(reader);
        reader.finishNow = true;
        ex.cancel("Looper", "try again", "alice", StopOutcome.RETRY);
        run.finish(reader);
        assertNull(run.error.get());
        assertEquals("done", ex.getContext().getVariable("out_text"), "the second attempt finished");
        assertEquals(2, reader.briefings.size(), "two attempts");

        // an agent whose tool is not known to only read: the retry is refused, and with nobody to ask the run is suspended
        Model writer = new Model(false);
        HarnessExecutor ex2 = executor("MockTool", "", writer, RunJournal.inMemory(), null);
        Run run2 = new Run(ex2).start(writer);
        ex2.cancel("Looper", "try again", "alice", StopOutcome.RETRY);
        run2.finish(writer);
        assertInstanceOf(RunSuspended.class, run2.error.get());
    }

    @Test
    void aSteerReachesTheAgentIsJournaledAndIsCheckedBeforeItGoesIn() throws Exception {
        Model model = new Model(false);
        RunJournal journal = RunJournal.inMemory();
        HarnessExecutor ex = executor("MockTool", "", model, journal, null);
        List<String> events = traceTypes(ex);
        Run run = new Run(ex).start(model);

        assertEquals(0, ex.steer("Looper", "   ", "alice"), "an empty message is not a steer");
        assertEquals(0, ex.steer("Looper", "x".repeat(InterventionCoordinator.MAX_STEER_CHARS + 1), "alice"), "too long");
        assertEquals(0, ex.steer("Nobody", "hello", "alice"), "no such agent is running");
        assertEquals(1, ex.steer("Looper", "focus on the EU", "alice"));
        assertEquals(Map.of(ex.runningAgents().keySet().iterator().next(), "Looper"), ex.runningAgents());
        model.finishNow = true;
        run.finish(model);

        assertNull(run.error.get());
        assertEquals("done", ex.getContext().getVariable("out_text"));
        assertTrue(journal.all().values().stream().anyMatch(e -> "steer".equals(e.kind()) && "focus on the EU".equals(((Map<?, ?>) e.value()).get("text"))
                && "alice".equals(((Map<?, ?>) e.value()).get("origin"))));
        assertTrue(events.contains("intervention:steer:"), events.toString());
    }

    @Test
    void anAgentsGuardRefusesASteerThatCarriesPersonalData() throws Exception {
        Model model = new Model(false);
        RunJournal journal = RunJournal.inMemory();
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        LoomScript parsed = new LoomParser(new Lexer("""
                agent Looper { model: "test" system: "You are Looper." tools: [MockTool] steerable: true guard { pii: block } }
                workflow Main() { delegate "go" to Looper -> out_text }
                """).tokenize()).parseScript();
        HarnessExecutor ex = new HarnessExecutor(parsed, tools, model.factory());
        ex.setJournal(journal);
        ex.initialize();
        Run run = new Run(ex).start(model);

        assertEquals(0, ex.steer("Looper", "email the report to jane.doe@example.com", "alice"), "pii: block keeps it from the agent");
        assertTrue(journal.all().values().stream().noneMatch(e -> "steer".equals(e.kind())));
        model.finishNow = true;
        run.finish(model);
    }

    @Test
    void pausingAnAgentHoldsItAndResumingLetsItFinish() throws Exception {
        Model model = new Model(false);
        HarnessExecutor ex = executor("MockTool", "", model, RunJournal.inMemory(), null);
        Run run = new Run(ex).start(model);

        assertEquals(1, ex.pauseAgents("Looper", "ops"));
        model.finishNow = true;
        model.release.countDown();
        Thread.sleep(400);
        int whilePaused = model.calls.get();
        Thread.sleep(300);
        assertEquals(whilePaused, model.calls.get(), "no model call is made while the agent is paused");
        assertTrue(run.thread.isAlive());

        assertEquals(1, ex.resumeAgents("Looper", "ops"));
        run.thread.join(10_000);
        assertFalse(run.thread.isAlive());
        assertNull(run.error.get());
        assertEquals("done", ex.getContext().getVariable("out_text"));
    }

    @Test
    void anAgentThatIsNotDeclaredSteerableRefusesASteer() throws Exception {
        Model model = new Model(false);
        RunJournal journal = RunJournal.inMemory();
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        LoomScript parsed = new LoomParser(new Lexer("""
                agent Looper { model: "test" system: "You are Looper." tools: [MockTool] }
                workflow Main() { delegate "go" to Looper -> out_text }
                """).tokenize()).parseScript();
        HarnessExecutor ex = new HarnessExecutor(parsed, tools, model.factory());
        ex.setJournal(journal);
        ex.initialize();
        Run run = new Run(ex).start(model);

        assertEquals(0, ex.steer("Looper", "hello", "alice"));
        assertEquals(1, ex.wrapUp("Looper", "enough", "alice"), "wrap up and cancel do not need steerable");
        run.finish(model);
        assertTrue(journal.all().values().stream().noneMatch(e -> "steer".equals(e.kind())));
    }
}
