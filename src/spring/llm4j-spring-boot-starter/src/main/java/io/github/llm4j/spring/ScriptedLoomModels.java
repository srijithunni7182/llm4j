package io.github.llm4j.spring;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The models of an application's test: an agent answers what the test scripted for it, in order
 * (the last answer repeats), and {@code <Agent>#n} when nothing was scripted. No network, no key.
 * Classifiers and tools are not scripted here; use {@code weave test} for those.
 */
public final class ScriptedLoomModels implements LLMClientFactory {

    private final Map<String, List<String>> replies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    /** What the agent answers, in order. */
    public ScriptedLoomModels agent(String agent, String... answers) {
        replies.put(agent, List.of(answers));
        calls.remove(agent);
        return this;
    }

    /** How many times the agent was called. */
    public int calls(String agent) {
        AtomicInteger n = calls.get(agent);
        return n == null ? 0 : n.get();
    }

    @Override
    public LLMClient createClient(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String agent = system.replaceAll("(?s).*?You are (\\w+)\\..*", "$1");
                int n = calls.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
                List<String> scripted = replies.getOrDefault(agent, new ArrayList<>());
                String answer =
                        scripted.isEmpty()
                                ? agent + "#" + n
                                : scripted.get(Math.min(n, scripted.size()) - 1);
                return LLMResponse.builder()
                        .content(
                                "```json\n{\"thought\": \"t\", \"final_answer\": "
                                        + new com.fasterxml.jackson.databind.ObjectMapper()
                                                .valueToTree(answer)
                                        + "}\n```")
                        .model(model)
                        .tokenUsage(10, 5, 15)
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }
}
