import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.ToolException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.routing.FallbackRoutingStrategy;
import io.github.llm4j.routing.ProviderTier;
import io.github.llm4j.routing.RoutingLLMClient;
import io.github.llm4j.telemetry.ErrorCategory;
import io.github.llm4j.telemetry.ModelCallEvent;
import io.github.llm4j.telemetry.ModelStats;
import io.github.llm4j.telemetry.RunStats;
import io.github.llm4j.telemetry.ToolCallEvent;
import io.github.llm4j.telemetry.ToolStats;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * What every tool call and model call of an agent did, and the statistics of the run. No model and no key: the "model" is a script (behind a routing
 * client whose first provider is down, so you see a fail-over) that calls three tools several times and then answers. Run it with ./run.sh.
 */
public class CallTelemetryDemo {

    public static void main(String[] args) {
        // a primary provider that is down, and a backup that works: the routing client fails over, and the trace says so
        LLMClient down = new PrimaryProvider();
        AtomicInteger n = new AtomicInteger();
        String[][] script = {{"search", "agent telemetry"}, {"search", "nothing matches this"}, {"calc", "6*7"}, {"lookup", "customer 17"}, {"search", "context windows"}};
        LLMClient backup = new LLMClient() {
            @Override public LLMResponse chat(LLMRequest r) {
                int i = n.getAndIncrement();
                if (i < script.length) {
                    return LLMResponse.builder().content("looking").model("backup-model").tokenUsage(400 + 80 * i, 25, 425 + 80 * i).finishReason("tool_calls")
                            .toolCalls(List.of(new ToolCall("c" + i, script[i][0], Map.of("q", script[i][1])))).build();
                }
                return LLMResponse.builder().content("All done.").model("backup-model").tokenUsage(900, 40, 940).finishReason("stop").build();
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.of(chat(r)); }
            @Override public boolean supportsToolCalling() { return true; }
        };
        LLMClient model = RoutingLLMClient.builder().strategy(new FallbackRoutingStrategy())
                .addClient(ProviderTier.FAST_CHEAP, down).addClient(ProviderTier.BALANCED, backup).build();

        // three tools: one that returns an error as plain text, one that says exactly what went wrong, one that works
        Tool search = tool("search", q -> {
            pause(30);
            return q.contains("nothing") ? "Error: no results for '" + q + "'" : "3 results for " + q;
        });
        Tool lookup = tool("lookup", q -> {
            pause(12);
            throw new ToolException(ErrorCategory.PERMISSION_DENIED, "not allowed to read " + q);
        });
        Tool calc = tool("calc", q -> {
            pause(2);
            return "42";
        });

        // 1. one line per call, as it happens
        AgentEventListener narrator = new AgentEventListener() {
            @Override public void onThought(String t) {}
            @Override public void onAction(String tool, String input) {}
            @Override public void onObservation(String o) {}
            @Override public void onModelCall(ModelCallEvent e) {
                System.out.printf("model  #%d %-13s %4d ms  %4d+%-3d tokens  attempts=%d failovers=%s%n",
                        e.iteration(), e.model(), e.durationMillis(), e.promptTokens(), e.completionTokens(), e.attempts(), e.failovers());
            }
            @Override public void onToolCall(ToolCallEvent e) {
                System.out.printf("tool   %-7s %-14s %4d ms  %s%n", e.tool(), e.outcome(), e.durationMillis(),
                        e.failed() ? "FAILED: " + e.category() + (e.softFailure() ? " (the tool returned the error as text)" : "") : "ok");
            }
        };

        AgentResult result = ReActAgent.builder().llmClient(model).addTool(search).addTool(lookup).addTool(calc).addListener(narrator).maxIterations(20).build().run("Answer the question.");

        // 2. the statistics of the run, from the result
        RunStats stats = result.getStats();
        System.out.println();
        System.out.println("tools:");
        for (ToolStats t : stats.tools().values()) {
            System.out.printf("  %-7s %d calls, %s success, p50 %d ms, p95 %d ms, failures %s%n", t.tool(), t.calls(),
                    t.successRate().isPresent() ? String.format("%.0f%%", t.successRate().getAsDouble() * 100) : "n/a", t.latency().p50(), t.latency().p95(), t.failures());
        }
        System.out.println("models:");
        for (ModelStats m : stats.models().values()) {
            System.out.printf("  %-13s %d calls, %d tokens in, %d out, failovers %d, retries %d%n", m.model(), m.calls(), m.promptTokens(), m.completionTokens(), m.failovers(), m.retries());
        }
        System.out.println("answer: " + result.getFinalAnswer());
    }

    /** The provider that is down. */
    static final class PrimaryProvider implements LLMClient {
        @Override public LLMResponse chat(LLMRequest r) { throw new io.github.llm4j.exception.LLMException("primary provider unreachable"); }
        @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.empty(); }
        @Override public boolean supportsToolCalling() { return true; }
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @FunctionalInterface
    interface Body { String run(String q) throws Exception; }

    private static Tool tool(String name, Body body) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "demo tool " + name; }
            @Override public String execute(Map<String, Object> a) throws Exception { return body.run(String.valueOf(a.get("q"))); }
        };
    }
}
