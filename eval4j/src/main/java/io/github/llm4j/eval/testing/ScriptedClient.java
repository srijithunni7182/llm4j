package io.github.llm4j.eval.testing;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A model that answers from rules instead of the network: the first rule whose predicate matches
 * the request text wins, otherwise a default answer. Free and deterministic, so an agent's control
 * flow can be tested without a single paid call.
 *
 * <pre>{@code
 * LLMClient model = new ScriptedClient()
 *     .whenSeen("Round 1", ScriptedClient.reactFinal("It does not exist."))
 *     .otherwise(ScriptedClient.reactFinal("ok"));
 * }</pre>
 */
public final class ScriptedClient implements LLMClient {

    private record Rule(Predicate<String> when, String answer) {}

    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String fallback = reactFinal("ok");
    private volatile String modelName = "scripted";

    /** Answers with {@code answer} when the request text contains {@code needle}. */
    public ScriptedClient whenSeen(String needle, String answer) {
        return when(t -> t.contains(needle), answer);
    }

    public ScriptedClient when(Predicate<String> predicate, String answer) {
        rules.add(new Rule(predicate, answer));
        return this;
    }

    /** The answer when no rule matches. */
    public ScriptedClient otherwise(String answer) {
        this.fallback = answer;
        return this;
    }

    public ScriptedClient model(String name) {
        this.modelName = name;
        return this;
    }

    /** The ReAct final-answer envelope the library's agents expect. */
    public static String reactFinal(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \""
                + answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                + "\"}\n```";
    }

    /**
     * The ReAct envelope that makes an agent call {@code tool} with {@code argsJson}, e.g. {@code
     * {"query":"x"}}.
     */
    public static String reactCall(String tool, String argsJson) {
        return "```json\n{\"thought\": \"t\", \"action\": \""
                + tool
                + "\", \"action_input\": "
                + argsJson
                + "}\n```";
    }

    /** Every request seen so far, flattened to text. */
    public List<String> requests() {
        return requests;
    }

    public int calls() {
        return requests.size();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Message m : request.getMessages()) {
            sb.append(m.getContent()).append('\n');
        }
        String text = sb.toString();
        requests.add(text);
        String answer = fallback;
        for (Rule r : rules) {
            if (r.when().test(text)) {
                answer = r.answer();
                break;
            }
        }
        return LLMResponse.builder()
                .content(answer)
                .model(modelName)
                .tokenUsage(Math.max(1, text.length() / 4), Math.max(1, answer.length() / 4), 0)
                .build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }
}
