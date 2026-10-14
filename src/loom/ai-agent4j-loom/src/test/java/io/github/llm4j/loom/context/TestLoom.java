package io.github.llm4j.loom.context;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A Loom executor over a scripted model that reports the prompt size it was really sent (as a provider does), for the context tests: an agent calls a
 * tool {@code steps} times, with a big result each time, and then answers.
 */
final class TestLoom {

    final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
    final List<String> modelsCreated = Collections.synchronizedList(new ArrayList<>());
    final List<TraceEvent> trace = Collections.synchronizedList(new ArrayList<>());
    final ToolRegistry tools = new ToolRegistry();
    final HarnessExecutor executor;
    private final AtomicInteger calls = new AtomicInteger();
    /** A prompt above this many tokens is refused as the provider would refuse it. */
    volatile long refuseAbove = Long.MAX_VALUE;
    /** When set, every model call fails with this. */
    volatile RuntimeException failWith;

    TestLoom(Path dir, String source, int steps) {
        this(dir, source, steps, true);
    }

    TestLoom(Path dir, String source, int steps, boolean listen) {
        tools.register("lookup", lookup());
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        executor = new HarnessExecutor(script, tools, model -> {
            modelsCreated.add(model);
            return client(model, steps);
        });
        executor.setBaseDir(dir);
        executor.setHumanInterface(message -> "yes");
        if (listen) executor.addTraceListener(trace::add);
    }

    static Tool lookup() {
        return new Tool() {
            @Override public String getName() { return "lookup"; }
            @Override public String getDescription() { return "looks something up"; }
            @Override public String execute(Map<String, Object> args) { return "result for " + args.get("q") + ": " + "data ".repeat(300); }
        };
    }

    static String call(int i) {
        return "```json\n{\"thought\": \"look\", \"action\": \"lookup\", \"action_input\": {\"q\": \"topic " + i + "\"}}\n```";
    }

    static String done() {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"finished\"}\n```";
    }

    private LLMClient client(String model, int steps) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                requests.add(request);
                if (failWith != null) throw failWith;
                int i = calls.getAndIncrement();
                long prompt = CharsPerTokenEstimator.INSTANCE.prompt(request);
                if (prompt > refuseAbove) throw new io.github.llm4j.exception.ContextOverflowException("prompt is too long: " + prompt + " tokens");
                String content = i < steps ? call(i) : done();
                return LLMResponse.builder().content(content).model(model).tokenUsage((int) prompt, 20, (int) prompt + 20).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    long biggest() {
        long max = 0;
        synchronized (requests) {
            for (LLMRequest r : requests) max = Math.max(max, CharsPerTokenEstimator.INSTANCE.prompt(r));
        }
        return max;
    }

    void run() {
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());
    }

    static String script(String model, String contextBlock) {
        return "agent Researcher {\n  model: \"" + model + "\"\n  tools: [lookup]\n  max_iterations: 400\n  " + contextBlock + "\n}\n"
                + "workflow Main() { delegate \"research the topic\" to Researcher -> out }\n";
    }
}
