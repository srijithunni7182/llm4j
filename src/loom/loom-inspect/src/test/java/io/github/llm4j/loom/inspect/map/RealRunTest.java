package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mapper against what a real executor really emits, not events built by hand: a scripted model,
 * a tool, a sleep that pauses the run, and a second executor on the same journal that wakes it.
 * Everything goes through OTLP/JSON on its way to the console.
 */
class RealRunTest {

    private static final String RUN = "r-real";

    private static final String SCRIPT =
            """
            agent Researcher { model: "m" tools: [Lookup] }
            agent Writer { model: "m" }
            workflow Main() {
                delegate "find the answer" to Researcher -> found
                sleep 90s
                delegate "write it up: {found}" to Writer -> text
            }
            """;

    /** A clock tests move by hand. */
    private static final class Manual extends Clock {
        private Instant now = Instant.parse("2026-10-11T14:02:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TempDir Path dir;

    private final Manual clock = new Manual();
    private final RunRegistry registry = new RunRegistry();

    private static String call(String tool) {
        return "```json\n{\"thought\": \"use "
                + tool
                + "\", \"action\": \""
                + tool
                + "\", \"action_input\": {\"q\": \"x\"}}\n```";
    }

    private static String done(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer + "\"}\n```";
    }

    private HarnessExecutor executor(String instance, List<String> answers) {
        return executor(instance, answers, SCRIPT);
    }

    private HarnessExecutor executor(String instance, List<String> answers, String source) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        int[] next = {0};
        LLMClientFactory factory =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                String content =
                                        next[0] < answers.size()
                                                ? answers.get(next[0]++)
                                                : done("ok");
                                return LLMResponse.builder()
                                        .content(content)
                                        .model(model)
                                        .tokenUsage(10, 5, 15)
                                        .build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.of(chat(request));
                            }
                        };
        ToolRegistry tools = new ToolRegistry();
        tools.register(
                "Lookup",
                new Tool() {
                    @Override
                    public String getName() {
                        return "Lookup";
                    }

                    @Override
                    public String getDescription() {
                        return "test tool";
                    }

                    @Override
                    public String execute(Map<String, Object> args) {
                        return "the answer is 42";
                    }
                });
        tools.register(
                "Flaky",
                new Tool() {
                    @Override
                    public String getName() {
                        return "Flaky";
                    }

                    @Override
                    public String getDescription() {
                        return "always fails";
                    }

                    @Override
                    public String execute(Map<String, Object> args) {
                        return "error: 500 server error";
                    }
                });
        HarnessExecutor e = new HarnessExecutor(script, tools, factory);
        e.setClock(clock);
        e.setSleeper(d -> clock.advance(d));
        e.setRunId(RUN);
        e.setJournal(new FileRunJournal(dir.resolve("journal.json")));

        TraceMapper mapper = new TraceMapper(RUN, Detail.FULL);
        Resource resource = Resource.of("getviral-prod", instance);
        e.addTraceListener(
                event -> {
                    TraceMapper.Output out = mapper.map(event);
                    // through OTLP/JSON, as it would travel
                    for (OtlpJson.Logs l :
                            OtlpJson.decodeLogs(OtlpJson.encodeLogs(resource, "0.1", out.logs())))
                        registry.applyLogs(l.resource(), l.records());
                    for (OtlpJson.Traces t :
                            OtlpJson.decodeTraces(
                                    OtlpJson.encodeTraces(resource, "0.1", out.spans())))
                        registry.applySpans(t.resource(), t.spans());
                });
        e.initialize();
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> view, String key) {
        return (List<Map<String, Object>>) view.get(key);
    }

    @Test
    void aRealSleptRunIsReadCorrectlyAcrossTwoExecutors() {
        HarnessExecutor first = executor("i_1", List.of(call("Lookup"), done("forty-two")));
        assertThatThrownBy(() -> first.executeWorkflow("Main", Map.of()))
                .isInstanceOf(RunSuspended.class);

        Map<String, Object> asleep =
                registry.find("getviral-prod", Ids.traceId(RUN)).orElseThrow().view();
        assertThat(asleep.get("status")).isEqualTo("sleeping");
        assertThat(asleep.get("workflow")).isEqualTo("Main");
        assertThat(asleep.get("runId")).isEqualTo(RUN);
        assertThat(((Map<?, ?>) asleep.get("suspended")).get("reason")).isEqualTo("SLEEP");
        assertThat(((Map<?, ?>) asleep.get("totals")).get("tokens"))
                .isEqualTo(30L); // two calls of 15
        assertThat(list(asleep, "tools").get(0))
                .containsEntry("name", "Lookup")
                .containsEntry("calls", 1L)
                .containsEntry("errors", 0L);
        assertThat(list(asleep, "openSteps")).isEmpty();

        clock.advance(Duration.ofSeconds(90));
        HarnessExecutor second = executor("i_2", List.of(done("a write-up")));
        second.executeWorkflow("Main", Map.of());

        Map<String, Object> v =
                registry.find("getviral-prod", Ids.traceId(RUN)).orElseThrow().view();
        assertThat(v.get("status")).isEqualTo("finished");
        assertThat(v.get("startedAt")).isEqualTo("2026-10-11T14:02:00Z");
        Map<?, ?> totals = (Map<?, ?>) v.get("totals");
        assertThat(totals.get("tokens"))
                .as("the replayed first step is not counted twice")
                .isEqualTo(45L);
        assertThat(totals.get("calls")).isEqualTo(3L);
        assertThat(v.get("gaps")).isEqualTo(0L);
        assertThat(v.get("duplicates")).isEqualTo(0L);
        assertThat(list(v, "agents"))
                .extracting(a -> a.get("name"))
                .containsExactlyInAnyOrder("Researcher", "Writer");
        assertThat(list(v, "agents"))
                .allSatisfy(a -> assertThat(a.get("status")).isEqualTo("done"));
        assertThat(list(v, "openSteps")).isEmpty();
    }

    @Test
    void everyRecordTheRealRunProducesIsWellFormed() {
        List<LogRec> logs = new ArrayList<>();
        List<SpanRec> spans = new ArrayList<>();
        HarnessExecutor e = executor("i_1", List.of(call("Lookup"), done("x")));
        TraceMapper mapper = new TraceMapper(RUN, Detail.FULL);
        e.addTraceListener(
                event -> {
                    TraceMapper.Output out = mapper.map(event);
                    logs.addAll(out.logs());
                    spans.addAll(out.spans());
                });
        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of()))
                .isInstanceOf(RunSuspended.class);
        assertThat(logs)
                .isNotEmpty()
                .allSatisfy(
                        l -> {
                            assertThat(l.traceId()).isEqualTo(Ids.traceId(RUN));
                            assertThat(l.eventName()).startsWith("loom.");
                        });
        assertThat(spans)
                .allSatisfy(
                        s -> {
                            assertThat(s.traceId()).isEqualTo(Ids.traceId(RUN));
                            assertThat(s.spanId()).matches("[0-9a-f]{16}");
                            assertThat(s.endNanos()).isGreaterThanOrEqualTo(s.startNanos());
                        });
    }

    private static final String NESTED =
            """
            agent Writer { model: "m" }
            workflow Main() {
                delegate "start" to Writer -> first
                loop until (first == "never") max 2 {
                    delegate "round {_loopRound}" to Writer -> draft
                }
                alt (first == "ok") {
                    delegate "then side" to Writer -> side
                } else {
                    delegate "else side" to Writer -> side
                }
            }
            """;

    @Test
    @SuppressWarnings("unchecked")
    void everyStepARealRunReportsLandsOnANodeOfTheGraph() {
        HarnessExecutor e = executor("i_1", List.of(), NESTED);
        e.executeWorkflow("Main", Map.of());
        Map<String, Object> view =
                registry.find("getviral-prod", Ids.traceId(RUN)).orElseThrow().view();
        Map<String, Object> steps = (Map<String, Object>) view.get("steps");

        List<Map<String, Object>> nodes =
                (List<Map<String, Object>>)
                        ((List<Map<String, Object>>)
                                        GraphView.of(e.observable().script()).get("workflows"))
                                .get(0)
                                .get("nodes");
        List<String> paths = nodes.stream().map(n -> (String) n.get("path")).toList();
        assertThat(paths).contains("Main/s0", "Main/s1", "Main/s1/r.0", "Main/s2", "Main/s2/b0");
        assertThat(steps.keySet())
                .isNotEmpty()
                .allSatisfy(k -> assertThat(paths).contains((String) k));
        assertThat((Map<String, Object>) steps.get("Main/s1/r.0"))
                .containsEntry("runs", 2); // two rounds, one place
        assertThat((Map<String, Object>) steps.get("Main/s0")).containsEntry("state", "done");
    }

    private static final String MISBEHAVING =
            """
            agent Fetcher { model: "m" tools: [Flaky] max_iterations: 8 }
            agent Writer { model: "m" }
            workflow Main() {
                delegate "fetch the numbers" to Fetcher -> got
                delegate "write two sentences about them" to Writer -> text
            }
            """;

    @Test
    @SuppressWarnings("unchecked")
    void aRealRunThatMisbehavesRaisesTheIncidentsItShould() {
        String flaky =
                "```json\n{\"thought\": \"again\", \"action\": \"Flaky\", \"action_input\": {\"q\": \"numbers\"}}\n```";
        HarnessExecutor e =
                executor(
                        "i_1",
                        List.of(
                                flaky,
                                flaky,
                                flaky,
                                flaky,
                                done("could not fetch"),
                                done("87% of 1200 users in 2019 liked it, across 340 sessions")),
                        MISBEHAVING);
        e.executeWorkflow("Main", Map.of());
        Map<String, Object> view =
                registry.find("getviral-prod", Ids.traceId(RUN)).orElseThrow().view();
        List<Map<String, Object>> incidents = (List<Map<String, Object>>) view.get("incidents");
        assertThat(incidents).extracting(i -> i.get("kind")).contains("LOOPING", "SUSPECT_OUTPUT");
        Map<String, Object> suspect =
                incidents.stream()
                        .filter(i -> "SUSPECT_OUTPUT".equals(i.get("kind")))
                        .findFirst()
                        .orElseThrow();
        assertThat(suspect).containsEntry("agent", "Writer").containsEntry("severity", "high");
        Map<String, Object> loop =
                incidents.stream()
                        .filter(i -> "LOOPING".equals(i.get("kind")))
                        .findFirst()
                        .orElseThrow();
        assertThat(loop).containsEntry("agent", "Fetcher");
        // the run finished, so every incident is over, and says why
        assertThat(incidents)
                .allSatisfy(
                        i ->
                                assertThat(i)
                                        .containsEntry("state", "closed")
                                        .containsEntry("closeReason", "the run ended"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aToolThatFailsThreeTimesInARealRunIsFlagged() {
        // the agent runtime itself stops an identical failing call from being repeated, so a
        // failing streak needs three different calls
        String[] calls = new String[3];
        for (int i = 0; i < 3; i++)
            calls[i] =
                    "```json\n{\"thought\": \"try\", \"action\": \"Flaky\", \"action_input\": {\"q\": \"attempt-"
                            + i
                            + "\"}}\n```";
        HarnessExecutor e =
                executor(
                        "i_1",
                        List.of(
                                calls[0],
                                calls[1],
                                calls[2],
                                done("could not fetch"),
                                done("fine")),
                        MISBEHAVING);
        e.executeWorkflow("Main", Map.of());
        List<Map<String, Object>> incidents =
                (List<Map<String, Object>>)
                        registry.find("getviral-prod", Ids.traceId(RUN))
                                .orElseThrow()
                                .view()
                                .get("incidents");
        Map<String, Object> failing =
                incidents.stream()
                        .filter(i -> "FAILED".equals(i.get("kind")))
                        .findFirst()
                        .orElseThrow();
        assertThat(failing).containsEntry("agent", "Fetcher");
        assertThat(String.valueOf(failing.get("title"))).contains("Flaky");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSameIncidentsAreRaisedWhenNoTextLeavesTheProcess() {
        // metadata detail: no prompt, answer or tool input is sent, only hashes and counts
        HarnessExecutor e =
                executor(
                        "i_1",
                        List.of(done("a fine answer with 87% and 1200 and 2019 and 340")),
                        SCRIPT);
        // the standard script has a Researcher with a tool; use the misbehaving one at metadata
        // level through a second mapper
        TraceMapper mapper = new TraceMapper(RUN, Detail.METADATA);
        RunRegistry local = new RunRegistry();
        HarnessExecutor w =
                executor(
                        "i_2",
                        List.of(done("87% of 1200 users in 2019 liked it, across 340 sessions")),
                        "agent Writer { model: \"m\" }\nworkflow Main() { delegate \"write two sentences\" to Writer -> text }\n");
        Resource resource = Resource.of("p", "i_9");
        w.addTraceListener(
                event -> {
                    TraceMapper.Output out = mapper.map(event);
                    local.applyLogs(resource, out.logs());
                    local.applySpans(resource, out.spans());
                });
        w.executeWorkflow("Main", Map.of());
        Map<String, Object> view = local.find("p", Ids.traceId(RUN)).orElseThrow().view();
        List<Map<String, Object>> incidents = (List<Map<String, Object>>) view.get("incidents");
        assertThat(incidents).extracting(i -> i.get("kind")).contains("SUSPECT_OUTPUT");
        assertThat(String.valueOf(view)).doesNotContain("liked it");
    }
}
