package io.github.llm4j.loom.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V6.1–V6.9 and V7.1–V7.3. */
class ApprovalsAndSettingsTest {

    @TempDir
    Path dir;

    final List<String> tasks = Collections.synchronizedList(new ArrayList<>());
    final List<String> questions = Collections.synchronizedList(new ArrayList<>());
    final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    final List<String> sessions = Collections.synchronizedList(new ArrayList<>());
    final List<Map<String, Object>> published = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger calculated = new AtomicInteger();

    final Tool publish = new Tool() {
        @Override public String getName() { return "Publish"; }
        @Override public String getDescription() { return "Publishes a post"; }
        @Override public String execute(Map<String, Object> args) {
            published.add(args);
            return "published";
        }
    };

    final Tool calc = new Tool() {
        @Override public String getName() { return "Calc"; }
        @Override public String getDescription() { return "Adds"; }
        @Override public String execute(Map<String, Object> args) {
            calculated.incrementAndGet();
            return "2";
        }
    };

    LLMClient model(List<String> answers) {
        AtomicInteger n = new AtomicInteger();
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                tasks.add(r.getMessages().get(r.getMessages().size() - 1).getContent());
                int i = n.getAndIncrement();
                String c = i < answers.size() ? answers.get(i) : ToolsTest.done("ok");
                return LLMResponse.builder().content(c).model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
    }

    HarnessExecutor executor(String source, List<String> answers, HumanInterface human, RunJournal journal) {
        ToolRegistry registry = new ToolRegistry();
        registry.register("Publish", publish);
        registry.register("Calc", calc);
        LLMClient m = model(answers);
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), registry, x -> m);
        if (human != null) e.setHumanInterface(human);
        if (journal != null) e.setJournal(journal);
        e.setAuditLogger(new AuditLogger() {
            @Override public void logAgentDecision(AuditEvent event) { }
            @Override public void logToolExecution(String s, String t, String i, String o, Instant ts) {
                audit.add("tool " + t);
                sessions.add(s);
            }
            @Override public void logPromptUsage(String s, String p, String v, Instant ts) { }
            @Override public void logConversationEvent(String s, String u, String type, Map<String, Object> data) {
                audit.add(type + " " + data);
                sessions.add(s);
            }
        });
        e.initialize();
        return e;
    }

    HumanInterface answering(String answer) {
        return new HumanInterface() {
            @Override public String promptHuman(String message) {
                questions.add(message);
                return answer;
            }
        };
    }

    static final String SCRIPT = """
            agent Poster { model: "m" tools: [Publish, Calc] approve: [Publish] }
            workflow Main() { delegate "post it" to Poster -> result }
            """;

    static final List<String> PUBLISH_THEN_DONE = List.of(
            ToolsTest.call("Publish", "{\"post\": \"hello\", \"channel\": \"main\"}"), ToolsTest.done("posted"));

    @Test
    void v6_1_approvedCallsRun() {
        executor(SCRIPT, PUBLISH_THEN_DONE, answering("yes"), null).executeWorkflow("Main", Map.of());
        assertThat(published).hasSize(1);
        assertThat(questions).singleElement().satisfies(q -> assertThat(q)
                .contains("Agent Poster wants to call Publish with").contains("hello").contains("Reason: use Publish").endsWith("Approve? yes/no"));
        assertThat(audit).anySatisfy(a -> assertThat(a).startsWith("approval_requested"))
                .anySatisfy(a -> assertThat(a).startsWith("approval_granted"));
    }

    @Test
    void v6_2_rejectedCallsDontRunAndTheAgentIsTold() {
        executor(SCRIPT, PUBLISH_THEN_DONE, answering("no"), null).executeWorkflow("Main", Map.of());
        assertThat(published).isEmpty();
        assertThat(tasks.get(1)).containsIgnoringCase("rejected");
        assertThat(audit).anySatisfy(a -> assertThat(a).startsWith("approval_rejected"));
    }

    @Test
    void v6_3_otherToolsDontAsk() {
        executor(SCRIPT, List.of(ToolsTest.call("Calc", "{}"), ToolsTest.done("2")), answering("yes"), null)
                .executeWorkflow("Main", Map.of());
        assertThat(calculated.get()).isEqualTo(1);
        assertThat(questions).isEmpty();
    }

    @Test
    void v6_4_aDurableRunPausesForTheAnswerAndNeverAsksTwice() {
        RunJournal journal = new FileRunJournal(dir.resolve("j.json"));
        HumanInterface later = new HumanInterface() {
            @Override public String promptHuman(String message) { throw new UnsupportedOperationException(); }
            @Override public String promptHuman(String stepId, String message) {
                questions.add(message);
                throw new RunSuspended(stepId, message);
            }
        };
        String[] key = new String[1];
        assertThatThrownBy(() -> executor(SCRIPT, PUBLISH_THEN_DONE, later, journal).executeWorkflow("Main", Map.of()))
                .isInstanceOfSatisfying(RunSuspended.class, s -> {
                    assertThat(s.reason()).isEqualTo(RunSuspended.Reason.HUMAN);
                    key[0] = s.stepId();
                });
        assertThat(key[0]).matches("Main/s0#approve:Publish:[0-9a-f]{12}");
        assertThat(published).isEmpty();

        journal.answer(key[0], "yes");
        HarnessExecutor resumed = executor(SCRIPT, PUBLISH_THEN_DONE, later, new FileRunJournal(dir.resolve("j.json")));
        resumed.executeWorkflow("Main", Map.of());
        assertThat(published).hasSize(1);
        assertThat(questions).hasSize(1); // asked once, ever
        assertThat(resumed.getContext().getVariable("result")).isEqualTo("posted");
    }

    @Test
    void v6_5_and_v6_6_keysFollowTheArgumentsNotTheirOrder() throws Exception {
        java.lang.reflect.Method key = Class.forName("io.github.llm4j.loom.execution.ApprovalGate")
                .getDeclaredMethod("key", String.class, String.class, Map.class);
        key.setAccessible(true);
        java.util.LinkedHashMap<String, Object> ab = new java.util.LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", Map.of("y", 2, "x", 1));
        java.util.LinkedHashMap<String, Object> ba = new java.util.LinkedHashMap<>();
        ba.put("b", Map.of("x", 1, "y", 2));
        ba.put("a", 1);
        Object k1 = key.invoke(null, "S", "Publish", ab);
        assertThat(key.invoke(null, "S", "Publish", ba)).isEqualTo(k1); // V6.6
        assertThat(key.invoke(null, "S", "Publish", Map.of("a", 2))).isNotEqualTo(k1); // V6.5
        assertThat(key.invoke(null, "S", "Other", ab)).isNotEqualTo(k1);
    }

    @Test
    void v6_5b_differentArgumentsAreAskedAgain() {
        RunJournal journal = RunJournal.inMemory();
        executor(SCRIPT, PUBLISH_THEN_DONE, answering("yes"), journal);
        List<String> other = List.of(ToolsTest.call("Publish", "{\"post\": \"changed\"}"), ToolsTest.done("posted"));
        journal.all(); // nothing recorded yet: the run above wasn't executed
        executor(SCRIPT, PUBLISH_THEN_DONE, answering("yes"), journal).executeWorkflow("Main", Map.of());
        // re-running the same step with other arguments asks again (fresh journal entry for the step result aside)
        RunJournal fresh = RunJournal.inMemory();
        journal.all().forEach((k, v) -> { if (k.contains("#approve:")) fresh.put(k, v); });
        executor(SCRIPT, other, answering("yes"), fresh).executeWorkflow("Main", Map.of());
        assertThat(questions).hasSize(2);
    }

    @Test
    void v6_7_approvalNamesAndAHumanAreRequired() {
        assertThatThrownBy(() -> executor("agent P { model: \"m\" tools: [Calc] approve: [Publish] }", List.of(), answering("y"), null))
                .hasMessageContaining("approve: Publish is not one of its tools [Calc]");
        assertThatThrownBy(() -> executor(SCRIPT, List.of(), null, null))
                .hasMessageContaining("approve needs someone to ask");
    }

    @Test
    void v6_8_approveAll() {
        executor("""
                agent Poster { model: "m" tools: [Publish, Calc] approve: all }
                workflow Main() { delegate "go" to Poster -> r }
                """, List.of(ToolsTest.call("Calc", "{}"), ToolsTest.done("2")), answering("yes"), null)
                .executeWorkflow("Main", Map.of());
        assertThat(questions).hasSize(1);
        assertThat(calculated.get()).isEqualTo(1);
    }

    @Test
    void v6_9_personalDataIsMaskedInTheAuditLog() {
        executor(SCRIPT, List.of(ToolsTest.call("Publish", "{\"to\": \"jane.doe@example.com\"}"), ToolsTest.done("ok")),
                answering("yes"), null).executeWorkflow("Main", Map.of());
        assertThat(audit).filteredOn(a -> a.startsWith("approval_")).isNotEmpty()
                .allSatisfy(a -> assertThat(a).doesNotContain("jane.doe@example.com"));
    }

    @Test
    void v7_1_maxIterationsBoundsTheAgent() {
        List<String> forever = new ArrayList<>();
        for (int i = 0; i < 10; i++) forever.add(ToolsTest.call("Calc", "{}"));
        executor("""
                agent Loopy { model: "m" tools: [Calc] max_iterations: 2 }
                workflow Main() { delegate "go" to Loopy -> r }
                """, forever, null, null).executeWorkflow("Main", Map.of());
        assertThat(tasks).hasSize(2);
    }

    @Test
    void v7_2_maxIterationsMustBePositive() {
        assertThatThrownBy(() -> new LoomParser(new Lexer("agent A { model: \"m\" max_iterations: 0 }").tokenize()).parseScript())
                .hasMessageContaining("max_iterations must be a positive whole number");
    }

    @Test
    void v7_3_agentsWriteToTheRunsAuditLog() {
        executor(SCRIPT, List.of(ToolsTest.call("Calc", "{}"), ToolsTest.done("2")), answering("yes"), null)
                .executeWorkflow("Main", Map.of());
        assertThat(audit).contains("tool Calc");
        assertThat(sessions).doesNotContainNull().allSatisfy(s -> assertThat(s).isEqualTo(sessions.get(0)));
    }
}
