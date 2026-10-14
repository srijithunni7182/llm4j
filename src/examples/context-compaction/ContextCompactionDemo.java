import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.context.CompactionEvent;
import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.context.ContextPolicy;
import io.github.llm4j.context.ContextUsage;
import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * An agent whose prompt would outgrow the model's context window, kept inside it by compaction. No model and no key: the "model" is a script that
 * calls a search tool 25 times and then answers, and the "summariser" is a stand-in for a cheaper model. Run it with ./run.sh.
 */
public class ContextCompactionDemo {

    static final int WINDOW = 4_000;
    static final int STEPS = 25;

    public static void main(String[] args) {
        AtomicInteger calls = new AtomicInteger();
        long[] biggest = {0};

        // the agent's model: asks for a search each time, then answers; it reports the prompt size it was sent, as a real provider would
        LLMClient model = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                int n = calls.getAndIncrement();
                long prompt = CharsPerTokenEstimator.INSTANCE.prompt(request);
                biggest[0] = Math.max(biggest[0], prompt);
                LLMResponse.Builder b = LLMResponse.builder().model("demo").tokenUsage((int) prompt, 20, (int) prompt + 20);
                if (n < STEPS) {
                    return b.content("looking").finishReason("tool_calls")
                            .toolCalls(List.of(new ToolCall("c" + n, "search", Map.of("q", "plan " + n)))).build();
                }
                return b.content("Plan 24 is the cheapest, at 500.").finishReason("stop").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }

            @Override
            public boolean supportsToolCalling() {
                return true;
            }
        };

        // a stand-in for a cheaper model that writes the summaries
        LLMClient summariser = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder().content("Searched plans 0 to N; none cheaper than 500 so far.").model("cheap").tokenUsage(900, 40, 940).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };

        Tool search = new Tool() {
            @Override public String getName() { return "search"; }
            @Override public String getDescription() { return "searches the plans"; }
            @Override public Map<String, Object> getParametersSchema() { return ToolSchema.permissive(); }
            @Override public String execute(Map<String, Object> input) { return "Results for " + input.get("q") + ": " + "details ".repeat(150); }
        };

        AgentEventListener listener = new AgentEventListener() {
            @Override public void onThought(String thought) {}
            @Override public void onAction(String tool, String input) {}
            @Override public void onObservation(String observation) {}

            @Override
            public void onContextUsage(ContextUsage u) {
                u.fraction().ifPresent(f -> System.out.printf("call %2d: context %3.0f%% full (%s)%n", calls.get() + 1, f * 100, u.source()));
            }

            @Override
            public void onCompaction(CompactionEvent e) {
                System.out.printf("         -> %s %s: %d -> %d tokens%s%s%n", e.result().strategy(), e.result().outcome(), e.result().tokensBefore(),
                        e.result().tokensAfter(), e.result().note() != null ? " (" + e.result().note() + ")" : "",
                        e.result().summarizerUsage() != null ? " [summary cost " + e.result().summarizerUsage().promptTokens() + " + "
                                + e.result().summarizerUsage().completionTokens() + " tokens]" : "");
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .llmClient(model)
                .addTool(search)
                .maxIterations(100)
                .contextPolicy(ContextPolicy.builder().window(ContextWindow.of(WINDOW)).keepRecent(4).build())
                .compaction(ContextCompactors.chain(
                        ContextCompactors.trimToolResults(300),        // shorten big results first: free
                        ContextCompactors.summarize(summariser, 150),  // then notes on the oldest steps
                        ContextCompactors.dropOldest()))               // and drop the oldest if that is not enough
                .addListener(listener)
                .build();

        AgentResult result = agent.run("Find the cheapest plan.");
        System.out.println();
        System.out.println("Answer: " + result.getFinalAnswer());
        System.out.printf("%d model calls in the run (the summaries included); the biggest prompt was %d tokens, window %d.%n",
                result.getUsage().getLlmCalls(), biggest[0], WINDOW);
        System.out.println("Without compaction the last prompt would have been about " + (STEPS * 340) + " tokens.");
    }
}
