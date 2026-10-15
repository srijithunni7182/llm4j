import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.context.CompactionRequest;
import io.github.llm4j.context.CompactionResult;
import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.loom.context.WindowLayers;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A Loom script whose agent would outgrow its model's window, kept inside it by the script's {@code context} block. No model and no key: the model is a
 * script. The window comes from research.windows; the strategy "keep_newest" is ours. Run it with ./run.sh.
 */
public class LoomContextDemo {

    static final int STEPS = 30;

    /** Our own strategy: keeps the first message and the newest quarter of the rest. */
    static final class KeepNewest implements ContextCompactor {
        @Override
        public CompactionResult compact(List<Message> messages, CompactionRequest request) {
            int n = messages.size();
            int from = Math.max(1, n - Math.max(request.keepRecent(), n / 4));
            List<Message> out = new ArrayList<>();
            for (int i = 0; i < n; i++) if (i == 0 || i >= from) out.add(messages.get(i));
            return CompactionResult.of(this, messages, out, request);
        }
        @Override public String name() { return "keep_newest"; }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getProperty("user.dir"));
        var script = new LoomLoader().load(dir.resolve("research.loom").toString());

        AtomicInteger calls = new AtomicInteger();
        long[] biggest = {0};
        LLMClient model = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                int i = calls.getAndIncrement();
                long prompt = CharsPerTokenEstimator.INSTANCE.prompt(request);
                biggest[0] = Math.max(biggest[0], prompt);
                String reply = i < STEPS
                        ? "```json\n{\"thought\": \"look\", \"action\": \"lookup\", \"action_input\": {\"q\": \"topic " + i + "\"}}\n```"
                        : "```json\n{\"thought\": \"done\", \"final_answer\": \"Findings gathered from " + STEPS + " lookups.\"}\n```";
                return LLMResponse.builder().content(reply).model("demo-model").tokenUsage((int) prompt, 20, (int) prompt + 20).build();
            }
            @Override public Stream<LLMResponse> chatStream(LLMRequest request) { return Stream.of(chat(request)); }
        };

        ToolRegistry tools = new ToolRegistry();
        tools.register("lookup", new Tool() {
            @Override public String getName() { return "lookup"; }
            @Override public String getDescription() { return "looks a topic up"; }
            @Override public String execute(Map<String, Object> a) { return "result for " + a.get("q") + ": " + "detail ".repeat(120); }
        });

        HarnessExecutor executor = new HarnessExecutor(script, tools, name -> model);
        executor.setBaseDir(dir);
        executor.setHumanInterface(message -> "yes");
        // 1. the window of each model comes from a file, not from the script (the CLI does this with --context-windows or <script>.windows)
        executor.setContextWindows(WindowLayers.load(List.of(dir.resolve("research.windows")), null, null));
        // 2. our own strategy, by the name the script uses (the CLI maps it in a .loct file)
        executor.registerCompactor("keep_newest", new KeepNewest());

        executor.addTraceListener(e -> {
            switch (e.type()) {
                case TraceEvent.CONTEXT -> { /* one line per call is too much for a demo: see the compactions */ }
                case TraceEvent.COMPACTION -> System.out.printf("compaction  %-26s %-9s %5s -> %-5s tokens%n", e.data().get("strategy"), e.data().get("outcome"), e.data().get("before"), e.data().get("after"));
                case TraceEvent.RUN_END -> System.out.printf("run ended   %s: %s model calls, %s tool calls, %s compactions%n", e.data().get("status"), e.data().get("model_calls"), e.data().get("tool_calls"), e.data().get("compactions"));
                default -> { }
            }
        });

        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

        var stats = executor.stats();
        System.out.println();
        System.out.printf("window 6000 tokens (5000 usable); the largest prompt sent was %d tokens%n", biggest[0]);
        System.out.println("model calls: " + stats.totalModelCalls() + ", tool calls: " + stats.totalToolCalls()
                + ", lookup success rate: " + stats.tool("lookup").successRate().getAsDouble() * 100 + "%");
    }
}
