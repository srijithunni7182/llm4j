package io.github.llm4j.loom.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.resume.TestClock;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.ratelimit.RateLimitInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Verification plan V8.1–V8.5. */
class ScheduleSyncTest {

    static final String DIGEST = """
            agent Writer { model: "test/model" system: "You are Writer." }
            agent AdminBot { model: "test/model" system: "You are AdminBot." }
            rate_limits { on_limit: suspend }
            workflow DailyDigest(topic) {
                delegate "digest of {topic}" to Writer -> digest
            }
            schedule MorningDigest {
                cron: "0 7 * * *"  timezone: "Asia/Kolkata"
                run: DailyDigest(topic="AI agents")
                misfire: run_once  overlap: skip
            }
            """;

    final TestClock clock = new TestClock();
    final InMemoryTriggerStore store = new InMemoryTriggerStore();

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    /** A model that answers, or refuses with a limit while {@code limitedUntil} is in the future. */
    LLMClient model(AtomicInteger calls, Instant[] limitedUntil) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                if (limitedUntil[0] != null && clock.instant().isBefore(limitedUntil[0])) {
                    throw new RateLimitException(RateLimitInfo.at(limitedUntil[0], RateLimitInfo.Scope.TOKENS, "test", "t"),
                            clock.instant());
                }
                int n = calls.incrementAndGet();
                return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"digest#" + n + "\"}\n```")
                        .model("test/model").tokenUsage(100, 50, 150).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    HarnessExecutor executor(String source, LLMClient model) {
        HarnessExecutor e = new HarnessExecutor(parse(source), new ToolRegistry(), m -> model);
        e.setClock(clock);
        e.setScriptRef("digest.loom");
        e.setTriggerStore(store);
        return e;
    }

    @Test
    void v8_1_scheduleBlocksBecomeStoredTriggers() {
        executor(DIGEST, model(new AtomicInteger(), new Instant[1])).initialize();
        Trigger t = store.get("schedule:digest.loom/MorningDigest").orElseThrow();
        assertThat(t.kind()).isEqualTo(Trigger.Kind.CRON);
        assertThat(t.target()).isEqualTo(new Trigger.StartWorkflow("digest.loom", "DailyDigest", Map.of("topic", "AI agents")));
        assertThat(t.nextFire()).isEqualTo(Instant.parse("2026-09-28T01:30:00Z"));
        assertThat(t.misfire()).isEqualTo(Trigger.Misfire.RUN_ONCE);
        assertThat(t.overlap()).isEqualTo(Trigger.Overlap.SKIP);
        // every: as an alternative
        LoomScript every = parse("""
                agent A { model: "m" }
                workflow W() { delegate "x" to A -> x }
                schedule Often { every: 6h run: W() misfire: skip overlap: queue }
                """);
        Trigger o = Schedules.toTrigger(every.getSchedules().get(0), "s", TestClock.T0);
        assertThat(o.kind()).isEqualTo(Trigger.Kind.EVERY);
        assertThat(o.nextFire()).isEqualTo(TestClock.T0.plus(Duration.ofHours(6)));
        assertThat(o.misfire()).isEqualTo(Trigger.Misfire.SKIP);
        assertThat(o.overlap()).isEqualTo(Trigger.Overlap.QUEUE);
    }

    @Test
    void v8_2_classicAgentSchedules() {
        String classic = """
                agent AdminBot { model: "test/model" system: "You are AdminBot." }
                schedule DailyCleanup {
                    initial_delay: "30s"
                    pattern: "24h"
                    agent: AdminBot
                    task: "Purge temporary RAG indices"
                }
                """;
        AtomicInteger calls = new AtomicInteger();
        HarnessExecutor e = executor(classic, model(calls, new Instant[1]));
        e.initialize();
        Trigger t = store.get("schedule:digest.loom/DailyCleanup").orElseThrow();
        assertThat(t.kind()).isEqualTo(Trigger.Kind.EVERY);
        assertThat(t.spec()).isEqualTo("PT24H");
        assertThat(t.nextFire()).isEqualTo(TestClock.T0.plusSeconds(30));
        assertThat(t.target()).isEqualTo(new Trigger.AgentTask("digest.loom", "AdminBot", "Purge temporary RAG indices"));
        assertThat(e.runAgentTask("AdminBot", "purge").getFinalAnswer()).isEqualTo("digest#1");
        assertThatThrownBy(() -> e.runAgentTask("Nobody", "x")).hasMessageContaining("Agent not found");

        // without a store it still runs in memory, as before (and stores nothing)
        HarnessExecutor inMemory = new HarnessExecutor(parse(classic), new ToolRegistry(), m -> model(calls, new Instant[1]));
        inMemory.initialize();
        inMemory.shutdown();
    }

    @Test
    void v8_3_editsAreReconciled() {
        executor(DIGEST, model(new AtomicInteger(), new Instant[1])).initialize();
        String id = "schedule:digest.loom/MorningDigest";
        Trigger fired = store.get(id).orElseThrow().fired(Instant.parse("2026-09-28T01:30:00Z"), "DONE",
                Instant.parse("2026-09-29T01:30:00Z"));
        store.upsert(fired);
        // unchanged script: nothing rewritten, the stored next slot stands
        executor(DIGEST, model(new AtomicInteger(), new Instant[1])).initialize();
        assertThat(store.get(id).orElseThrow().nextFire()).isEqualTo(Instant.parse("2026-09-29T01:30:00Z"));

        String edited = DIGEST.replace("0 7 * * *", "30 8 * * *") + """
                schedule Extra { every: 1h run: DailyDigest(topic="x") }
                """;
        clock.set("2026-09-28T02:00:00Z");
        executor(edited, model(new AtomicInteger(), new Instant[1])).initialize();
        Trigger changed = store.get(id).orElseThrow();
        assertThat(changed.spec()).isEqualTo("30 8 * * *");
        assertThat(changed.lastFire()).isEqualTo(Instant.parse("2026-09-28T01:30:00Z")); // kept
        assertThat(changed.nextFire()).isEqualTo(Instant.parse("2026-09-28T03:00:00Z"));
        assertThat(store.get("schedule:digest.loom/Extra")).isPresent();

        executor(DIGEST, model(new AtomicInteger(), new Instant[1])).initialize(); // Extra removed from the script
        assertThat(store.get("schedule:digest.loom/Extra").orElseThrow().enabled()).isFalse();
        // re-adding it enables it again
        executor(edited, model(new AtomicInteger(), new Instant[1])).initialize();
        assertThat(store.get("schedule:digest.loom/Extra").orElseThrow().enabled()).isTrue();
    }

    @Test
    void v8_4_badSchedulesAreReportedWithTheirLine() {
        assertThatThrownBy(() -> executor(DIGEST.replace("run: DailyDigest(", "run: NoSuchWorkflow("),
                model(new AtomicInteger(), new Instant[1])).initialize())
                .hasMessageContaining("line 7").hasMessageContaining("NoSuchWorkflow is not a workflow");
        assertThatThrownBy(() -> executor("""
                schedule S { every: 1h agent: Ghost task: "x" }
                """, model(new AtomicInteger(), new Instant[1])).initialize()).hasMessageContaining("agent Ghost is not defined");
        assertThatThrownBy(() -> parse(DIGEST.replace("0 7 * * *", "0 25 * * *"))).hasMessageContaining("line 8")
                .hasMessageContaining("hour field: 25");
        assertThatThrownBy(() -> parse(DIGEST.replace("Asia/Kolkata", "Mars/Base"))).hasMessageContaining("Unknown time zone");
        assertThatThrownBy(() -> parse("schedule S { cron: \"* * * * *\" every: 1h run: W() }")).hasMessageContaining("not both");
        assertThatThrownBy(() -> parse("schedule S { cron: \"* * * * *\" }")).hasMessageContaining("needs run");
        assertThatThrownBy(() -> parse("schedule S { run: W() }")).hasMessageContaining("needs cron");
        assertThatThrownBy(() -> parse("schedule S { run: W() agent: A cron: \"* * * * *\" }")).hasMessageContaining("not both");
        assertThatThrownBy(() -> parse("schedule S { cron: \"* * * * *\" run: W() misfire: later }")).hasMessageContaining("misfire must be");
        assertThatThrownBy(() -> parse("schedule S { cron: \"* * * * *\" run: W() overlap: maybe }")).hasMessageContaining("overlap must be");
        assertThatThrownBy(() -> parse("schedule S { cron: \"* * * * *\" run: W() colour: red }")).hasMessageContaining("Unknown schedule field");
    }

    @Test
    void v8_5_aScheduledRunPausesOnItsOwnWithoutHoldingUpTheSchedule() {
        AtomicInteger calls = new AtomicInteger();
        Instant[] limitedUntil = {Instant.parse("2026-09-28T03:00:00Z")};
        Map<String, RunJournal> journals = new ConcurrentHashMap<>();
        HarnessExecutor setup = executor(DIGEST, model(calls, limitedUntil));
        setup.initialize();

        // The host: every run gets its own journal and executor; paused runs leave resume triggers.
        TriggerTarget host = (t, runId) -> {
            HarnessExecutor e = executor(DIGEST, model(calls, limitedUntil));
            RunJournal journal = journals.computeIfAbsent(runId, k -> RunJournal.inMemory());
            e.setJournal(journal);
            e.setRunId(runId);
            e.initialize();
            String workflow = t.target() instanceof Trigger.StartWorkflow w ? w.workflow() : "DailyDigest";
            Map<String, String> args = t.target() instanceof Trigger.StartWorkflow w ? w.args() : Map.of();
            try {
                e.executeWorkflow(workflow, args);
                return TriggerTarget.Outcome.done();
            } catch (RunSuspended s) {
                return TriggerTarget.Outcome.suspended(s.resumeAt(), s.getMessage());
            }
        };
        TriggerRunner runner = new TriggerRunner(store, host, clock, "host");

        clock.set("2026-09-28T01:30:00Z");
        runner.tick();
        String runId = "MorningDigest@2026-09-28T01:30:00Z";
        Trigger resume = store.get(Trigger.resumeId(runId)).orElseThrow();
        assertThat(resume.nextFire()).isBetween(limitedUntil[0], limitedUntil[0].plusSeconds(30));
        assertThat(resume.attempts()).isEqualTo(1);
        assertThat(store.get("schedule:digest.loom/MorningDigest").orElseThrow().nextFire())
                .isEqualTo(Instant.parse("2026-09-29T01:30:00Z")); // the schedule moved on
        assertThat(journals.get(runId).get(HarnessExecutor.SUSPENSION)).isPresent();

        clock.set(resume.nextFire());
        runner.tick();
        assertThat(store.get(Trigger.resumeId(runId))).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }
}
