package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.StatsCollector;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.ToolException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The code samples in wiki/Telemetry-and-Statistics.md, compiled and run. Change a sample here and in the page together. */
class TelemetryDocsExamplesTest {

    @Test
    void classifyAnException() {
        // --- sample: classify
        ErrorCategory a = ErrorCategory.of(new SocketTimeoutException("Read timed out"));              // TIMEOUT
        ErrorCategory b = ErrorCategory.of(new IllegalStateException("backend", new IOException()));   // NETWORK: found in the cause
        ErrorCategory c = ErrorCategory.ofStatus(429);                                                 // RATE_LIMITED
        // --- end sample
        assertThat(a).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(b).isEqualTo(ErrorCategory.NETWORK);
        assertThat(c).isEqualTo(ErrorCategory.RATE_LIMITED);
    }

    // --- sample: tool
    /** A tool that says why it failed, and how to read its own results. */
    static final class LookupTool implements Tool {
        @Override public String getName() { return "lookup"; }
        @Override public String getDescription() { return "looks a record up by id"; }

        @Override
        public String execute(Map<String, Object> args) throws Exception {
            Object id = args.get("id");
            if (id == null) throw new ToolException(ErrorCategory.INVALID_ARGUMENTS, "the 'id' argument is missing");
            if ("42".equals(String.valueOf(id))) return "ERR 42: no record";                  // an error returned as text, in this tool's own style
            return "record " + id;
        }

        @Override
        public boolean isFailure(String result) {
            return result.startsWith("ERR ");                                                  // the default knows "Error: …", "Failed to …", HTTP statuses
        }
    }
    // --- end sample

    @Test
    void theToolSampleWorks() throws Exception {
        LookupTool t = new LookupTool();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> t.execute(Map.of())).isInstanceOf(ToolException.class)
                .satisfies(e -> assertThat(ErrorCategory.of(e)).isEqualTo(ErrorCategory.INVALID_ARGUMENTS));
        assertThat(t.isFailure(t.execute(Map.of("id", "42")))).isTrue();
        assertThat(t.isFailure(t.execute(Map.of("id", "7")))).isFalse();
    }

    @Test
    void readTheWordingOfAnErrorText() {
        // --- sample: wording
        boolean failed = io.github.llm4j.agent.ToolResults.looksLikeFailure("Error: Endpoint 'GET /x' not found in API specification");   // true
        ErrorCategory why = io.github.llm4j.agent.ToolResults.categoryOf("Error: Endpoint 'GET /x' not found in API specification");        // NOT_FOUND
        // --- end sample
        assertThat(failed).isTrue();
        assertThat(why).isEqualTo(ErrorCategory.NOT_FOUND);
    }

    @Test
    void addEventsUpIntoStatistics() {
        // --- sample: stats
        StatsCollector stats = new StatsCollector();
        stats.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(120).resultChars(800).build());
        stats.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(90).softFailure(ErrorCategory.NOT_FOUND).build());
        stats.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(2_000)
                .outcome(ToolOutcome.EXECUTION_ERROR, ErrorCategory.TIMEOUT, "SocketTimeoutException").build());

        RunStats now = stats.snapshot();
        double rate = now.tool("search").successRate().orElseThrow();                 // 1 of 3
        long p95 = now.tool("search").latency().p95();                                // 2000 ms
        Map<ErrorCategory, Long> why = now.tool("search").failures();                 // NOT_FOUND=1, TIMEOUT=1
        // --- end sample
        assertThat(rate).isEqualTo(1.0 / 3.0);
        assertThat(p95).isEqualTo(2_000);
        assertThat(why).containsEntry(ErrorCategory.NOT_FOUND, 1L).containsEntry(ErrorCategory.TIMEOUT, 1L);
    }

    @Test
    void listenToToolCalls() {
        // --- sample: listener
        AgentEventListener listener = new AgentEventListener() {
            @Override public void onThought(String thought) {}
            @Override public void onAction(String tool, String input) {}
            @Override public void onObservation(String observation) {}

            @Override
            public void onToolCall(ToolCallEvent e) {
                if (e.failed()) {
                    System.out.printf("%s failed (%s) after %d ms%n", e.tool(), e.category(), e.durationMillis());
                }
            }
        };
        // --- end sample
        TelemetryListener seen = new TelemetryListener();
        ReActAgent.builder().llmClient(Scripts.oneToolStep(true, "search", "x")).addTool(TestTool.returning("search", "Error: not found")).addListener(listener)
                .addListener(seen).build().run("Find it.");
        assertThat(seen.onlyTool().failed()).isTrue();
    }

    @Test
    void readTheStatisticsOfARun() {
        io.github.llm4j.LLMClient client = Scripts.toolSteps(true, "search", 4);
        TestTool searchTool = new TestTool("search", (a, n) -> {
            if (n == 3) throw new SocketTimeoutException("slow");
            return "result " + n;
        });
        ReActAgent agent = ReActAgent.builder().llmClient(client).addTool(searchTool).maxIterations(20).build();
        // --- sample: result
        AgentResult result = agent.run("Find the plans.");
        ToolStats search = result.getStats().tool("search");
        double rate = search.successRate().orElse(0);       // 0.75: three of four calls did what was asked
        long p95 = search.latency().p95();                  // milliseconds, from the calls that reached the tool
        Map<ErrorCategory, Long> why = search.failures();   // {TIMEOUT=1}
        // --- end sample
        assertThat(rate).isEqualTo(0.75);
        assertThat(p95).isGreaterThanOrEqualTo(0);
        assertThat(why).containsExactly(Map.entry(ErrorCategory.TIMEOUT, 1L));
    }

    @Test
    void timeInATest() {
        TestClock testClock = new TestClock();
        // --- sample: clock
        ReActAgent agent = ReActAgent.builder()
                .llmClient(Scripts.oneToolStep(true, "slow", "x"))
                .addTool(new TestTool("slow", (a, n) -> { testClock.advance(250); return "ok"; }))
                .clock(testClock)                                   // durations come from this clock, so a test can say exactly how long a tool took
                .build();
        // --- end sample
        AgentResult r = agent.run("Go.");
        assertThat(r.getStats().tool("slow").latency().max()).isEqualTo(250);
    }

    @Test
    void thePageShowsExactlyTheSamplesThatAreTested() throws Exception {
        String source = Files.readString(Path.of("src/test/java/io/github/llm4j/telemetry/TelemetryDocsExamplesTest.java"));
        String page = Files.readString(Path.of("wiki/Telemetry-and-Statistics.md"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("// --- sample: ([^\\n]+)\\n(.*?)\\n\\s*// --- end sample", java.util.regex.Pattern.DOTALL).matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            assertThat(page).as("the page shows the '" + m.group(1) + "' sample").contains(m.group(2).stripIndent().strip());
        }
        assertThat(found).isGreaterThanOrEqualTo(7);
    }
}
