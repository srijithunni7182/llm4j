package io.github.llm4j.loom.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.loom.trigger.FileTriggerStore;
import io.github.llm4j.loom.trigger.TriggerRunner;
import io.github.llm4j.loom.trigger.TriggerTarget;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verification plan E2E-2: an agent that quietly works through a backlog on an hourly allowance, driven
 * only by ticks every five minutes — it pauses when the hour's tokens are gone and carries on next hour.
 */
class BackgroundAgentEndToEndTest {

    static final String SCRIPT = """
            budget { tokens: 1000 per hour when_exhausted: suspend }
            agent Worker { model: "test/model" system: "You are Worker." budget { per_call: 200 } }
            workflow Main() {
                for each item in items {
                    delegate "handle {item}" to Worker -> {item}
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void e2e2_threeItemsAnHourUntilTheBacklogIsDone() {
        TestClock clock = new TestClock();
        AtomicInteger calls = new AtomicInteger();
        List<String> handled = new ArrayList<>();
        LLMClient model = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) { // every item costs 100 + 200 = 300 tokens
                int n = calls.incrementAndGet();
                String task = request.getMessages().get(request.getMessages().size() - 1).getContent();
                handled.add(task.replaceAll("(?s).*handle (item\\d+).*", "$1") + "@" + clock.instant());
                return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"done" + n + "\"}\n```")
                        .model("test/model").tokenUsage(100, 200, 300).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 10; i++) items.add("item" + i);
        FileTriggerStore store = new FileTriggerStore(dir.resolve("store"));
        HarnessExecutor[] last = new HarnessExecutor[1];
        java.util.function.Supplier<HarnessExecutor> host = () -> {
            HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript(),
                    new ToolRegistry(), m -> model);
            e.setJournal(new FileRunJournal(dir.resolve("run/journal.json")));
            e.setTriggerStore(store);
            e.setRunId("backlog");
            e.setClock(clock);
            e.setTokenEstimator(LimitScript.FIXED);
            e.initialize();
            e.getContext().setVariable("items", items);
            last[0] = e;
            return e;
        };
        assertThatThrownBy(() -> host.get().executeWorkflow("Main", Map.of())).isInstanceOf(RunSuspended.class);

        AtomicInteger resumes = new AtomicInteger();
        String[] finalOutcome = new String[1];
        TriggerRunner runner = new TriggerRunner(store, (t, runId) -> {
            resumes.incrementAndGet();
            try {
                host.get().executeWorkflow("Main", Map.of());
                finalOutcome[0] = "done";
                return TriggerTarget.Outcome.done();
            } catch (RunSuspended s) {
                return TriggerTarget.Outcome.suspended(s.resumeAt(), s.getMessage());
            }
        }, clock, "cron");
        for (int tick = 0; tick < 12 * 6 && finalOutcome[0] == null; tick++) {
            clock.advance(Duration.ofMinutes(5));
            runner.tick();
        }

        assertThat(finalOutcome[0]).isEqualTo("done");
        assertThat(resumes.get()).isEqualTo(3); // paused 3 times, resumed 3 times
        assertThat(calls.get()).isEqualTo(10);
        // three per hour: 10:00, 11:05, 12:05, then the last one at 13:05
        assertThat(handled).extracting(h -> h.substring(h.indexOf('@') + 12, h.indexOf('@') + 14))
                .containsExactly("10", "10", "10", "11", "11", "11", "12", "12", "12", "13");
        assertThat(last[0].spend().total().tokens()).isEqualTo(3000);
        assertThat(last[0].getRunBudget().spent().tokens()).isEqualTo(300); // this hour
        assertThat(last[0].getContext().getVariable("item10")).isEqualTo("done10");
        assertThat(store.all()).isEmpty();
    }
}
