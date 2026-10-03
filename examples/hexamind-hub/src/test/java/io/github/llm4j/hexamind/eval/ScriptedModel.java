package io.github.llm4j.hexamind.eval;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A model that answers from rules instead of the network: the first rule whose predicate matches the
 * request text wins, otherwise a short final answer. Costs nothing and is deterministic, so the debate's
 * control flow can be tested without a single paid call.
 */
public final class ScriptedModel implements LLMClientFactory {

    private record Rule(Predicate<String> when, String answer) {}

    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    /** Answers with {@code answer} when the request text contains {@code needle}. */
    public ScriptedModel whenSeen(String needle, String answer) {
        rules.add(new Rule(t -> t.contains(needle), answer));
        return this;
    }

    /** Wraps a plain answer in the ReAct final-answer envelope the agents expect. */
    public static String done(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \""
                + answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                + "\"}\n```";
    }

    /** Every request seen so far, as flattened text. */
    public List<String> requests() {
        return requests;
    }

    public int calls() {
        return requests.size();
    }

    @Override
    public LLMClient createClient(String modelName) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                StringBuilder sb = new StringBuilder();
                for (Message m : request.getMessages()) {
                    sb.append(m.getContent()).append('\n');
                }
                String text = sb.toString();
                requests.add(text);
                String answer = "ok";
                for (Rule r : rules) {
                    if (r.when().test(text)) {
                        answer = r.answer();
                        break;
                    }
                }
                return LLMResponse.builder()
                        .content(done(answer))
                        .model(modelName)
                        .tokenUsage(100, 50, 150)
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }
}
