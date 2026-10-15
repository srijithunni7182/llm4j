import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.loom.context.WindowLayers;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.inspect.map.Detail;
import io.github.llm4j.loom.inspect.map.TraceMapper;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.RunState;
import io.github.llm4j.loom.inspect.state.ToolSummary;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * What loom-inspect knows about a run's tools, models and context, with no model, no key and no server: a real Loom run is mapped to OpenTelemetry-shaped
 * records and fed to the same state a Fabric server keeps. Prints the tools table (health, success rate, failures by category, who uses it), the
 * models, each agent's context, the incidents, and the roll-up across runs. Run it with ./run.sh.
 */
public class InspectToolsDemo {

    static final int STEPS = 16;

    public static void main(String[] args) throws Exception {
        RunRegistry registry = new RunRegistry();
        for (int run = 1; run <= 2; run++) oneRun(registry, "demo-" + run);

        RunState state = registry.summaries().isEmpty() ? null : registry.find("demo", registry.summaries().get(0).get("traceId").toString()).orElse(null);
        if (state == null) throw new IllegalStateException("no run");
        Map<String, Object> v = state.view();

        System.out.println("TOOLS in one run");
        for (Map<String, Object> t : rows(v.get("tools"))) {
            Map<String, Object> h = map(t.get("health"));
            System.out.printf("  %-8s %-9s %-22s rate %-5s p50 %4s ms  failures %s  by agent %s%n", t.get("name"), h.get("state"), "(" + h.get("reason") + ")",
                    h.get("overallRate") == null ? "n/a" : String.format("%.0f%%", ((Double) h.get("overallRate")) * 100), t.get("p50Ms"), h.get("failures"), h.get("byAgent"));
        }
        System.out.println("MODELS");
        for (Map<String, Object> m : rows(v.get("models"))) {
            System.out.printf("  %-12s %s calls, %s tokens in, %s retries, p95 %s ms%n", m.get("name"), m.get("calls"), m.get("promptTokens"), m.get("retries"), m.get("p95Ms"));
        }
        System.out.println("CONTEXT");
        for (Map<String, Object> a : rows(v.get("agents"))) {
            Map<String, Object> c = map(a.get("context"));
            if (!c.isEmpty()) System.out.printf("  %-10s %s of %s tokens (%.0f%%), %s compactions%n", a.get("name"), c.get("used"), c.get("usable"), ((Double) c.get("fraction")) * 100, c.get("compactions"));
        }
        System.out.println("INCIDENTS");
        for (Map<String, Object> i : rows(v.get("incidents"))) System.out.printf("  %-6s %-9s %s%n", i.get("severity"), i.get("kind"), i.get("title"));

        System.out.println();
        System.out.println("ROLL-UP across " + registry.summaries().size() + " runs (what a Fabric server shows for a tools view), failing tools first");
        for (ToolSummary s : registry.toolRollup(null)) {
            System.out.printf("  %-8s %-9s %2d calls, %2d ran, %2d succeeded, p95 %5d ms  %s%n", s.name(), s.state(), s.calls(), s.ran(), s.successes(), s.p95Ms(), s.reason());
        }
    }

    private static void oneRun(RunRegistry registry, String runId) throws Exception {
        Path dir = Path.of(System.getProperty("user.dir"));
        var script = new LoomLoader().load(dir.resolve("research.loom").toString());
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register("Search", tool("Search", q -> "Error: 503 service unavailable for " + q + " " + "x".repeat(900))); // always fails, as text
        tools.register("Calc", tool("Calc", q -> "42"));                                                                 // always works
        tools.register("Lookup", tool("Lookup", q -> q.hashCode() % 3 == 0 ? "Error: 404 not found: " + q : "found " + q));  // flaky
        HarnessExecutor e = new HarnessExecutor(script, tools, model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                int i = calls.getAndIncrement();
                long prompt = CharsPerTokenEstimator.INSTANCE.prompt(request);
                String[] pick = {"Search", "Calc", "Lookup", "Search", "Lookup"};
                String content = i < STEPS
                        ? "```json\n{\"thought\": \"try\", \"action\": \"" + pick[i % pick.length] + "\", \"action_input\": {\"q\": \"item " + i + "\"}}\n```"
                        : "```json\n{\"thought\": \"done\", \"final_answer\": \"summary\"}\n```";
                return LLMResponse.builder().content(content).model(model).tokenUsage((int) prompt, 20, (int) prompt + 20).build();
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest r) { return Stream.of(chat(r)); }
        });
        e.setBaseDir(dir);
        e.setHumanInterface(m -> "yes");
        e.setRunId(runId);
        e.setContextWindows(WindowLayers.none().over("demo", ContextWindows.empty().with("demo-model", new ContextWindow(2600, 600))));
        TraceMapper mapper = new TraceMapper(runId, Detail.METADATA);
        Resource resource = Resource.of("demo", "i_" + runId);
        e.addTraceListener(event -> {
            TraceMapper.Output out = mapper.map(event);
            registry.applyLogs(resource, out.logs());
            registry.applySpans(resource, out.spans());
        });
        e.initialize();
        e.executeWorkflow("Main", Map.of());
    }

    @FunctionalInterface
    interface Body { String run(String q); }

    private static Tool tool(String name, Body body) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "demo tool " + name; }
            @Override public String execute(Map<String, Object> a) { return body.run(String.valueOf(a.get("q"))); }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o == null ? Map.of() : (Map<String, Object>) o;
    }
}
