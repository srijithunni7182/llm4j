package io.github.llm4j.loom.budget;

import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.MockTool;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Runs a Loom script against scripted models where every call is the verification plan's "standard
 * call": estimated prompt 100 tokens, reported usage 100 + 50. Agents are recognised by "You are X." in
 * their system prompt; a scripted agent answers from its list in order, everyone else answers
 * {@code X#n} as a final answer.
 */
final class BudgetScript {

    /** Every prompt is exactly 100 tokens. */
    static final TokenEstimator FIXED = new TokenEstimator() {
        @Override
        public long prompt(LLMRequest request) {
            return 100;
        }

        @Override
        public long completion(String content) {
            long chars = content == null ? 0 : content.length();
            return (chars * 11 + 39) / 40;
        }
    };

    /** test/model: $1.00 per million input tokens, $2.00 per million output tokens. */
    static final io.github.llm4j.budget.PriceTable PRICES = io.github.llm4j.budget.PriceTable.of(Map.of("test/model",
            new io.github.llm4j.budget.PriceTable.Price(new java.math.BigDecimal("1.00"), new java.math.BigDecimal("2.00"))));

    final HarnessExecutor executor;
    final AtomicInteger calls = new AtomicInteger();
    final Map<String, AtomicInteger> callsByAgent = new ConcurrentHashMap<>();
    final List<String> tasks = Collections.synchronizedList(new ArrayList<>());
    final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<String>> scripted = new ConcurrentHashMap<>();

    BudgetScript(String source) {
        this(source, null);
    }

    BudgetScript(String source, java.util.function.Consumer<HarnessExecutor> configure) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        ToolRegistry tools = new ToolRegistry();
        tools.register("Mock", new MockTool());
        LLMClientFactory factory = model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                calls.incrementAndGet();
                requests.add(request);
                String system = request.getMessages().get(0).getContent();
                String user = request.getMessages().get(request.getMessages().size() - 1).getContent();
                tasks.add(user);
                String agent = system.replaceAll("(?s).*?You are (\\w+)\\..*", "$1");
                int n = callsByAgent.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
                List<String> answers = scripted.get(agent);
                String content = answers != null && n <= answers.size() ? answers.get(n - 1)
                        : "```json\n{\"thought\": \"t\", \"final_answer\": \"" + agent + "#" + n + "\"}\n```";
                return LLMResponse.builder().content(content).model(model).tokenUsage(100, 50, 150).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        executor = new HarnessExecutor(script, tools, factory);
        executor.setTokenEstimator(FIXED);
        if (configure != null) configure.accept(executor);
        executor.initialize();
    }

    /** The agent's answers, in order: a tool call is {@code tool:T1}, anything else is a final answer. */
    BudgetScript answers(String agent, String... answers) {
        List<String> out = new ArrayList<>();
        for (String a : answers) {
            out.add(a.startsWith("tool:")
                    ? "```json\n{\"thought\": \"" + a.substring(5) + "\", \"action\": \"MockTool\", \"action_input\": {\"test\": 1}}\n```"
                    : "```json\n{\"thought\": \"t\", \"final_answer\": \"" + a + "\"}\n```");
        }
        scripted.put(agent, out);
        return this;
    }

    BudgetScript run() {
        executor.executeWorkflow("Main", Map.of());
        return this;
    }

    Object var(String name) {
        return executor.getContext().getVariable(name);
    }

    int calls(String agent) {
        AtomicInteger n = callsByAgent.get(agent);
        return n == null ? 0 : n.get();
    }
}
