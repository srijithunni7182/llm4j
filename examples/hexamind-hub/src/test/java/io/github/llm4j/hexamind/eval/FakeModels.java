package io.github.llm4j.hexamind.eval;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.stream.Stream;

/**
 * Stand-ins for the two real models, used by {@code -Deval.fake=true}: they exercise every code path
 * (search, final answer, rubric rating, A/B winner) for $0, so the whole pipeline and report can be
 * checked before a paid call. Their verdicts are deterministic but meaningless.
 */
public final class FakeModels {

    private FakeModels() {}

    private static String text(LLMRequest r) {
        StringBuilder sb = new StringBuilder();
        for (Message m : r.getMessages()) {
            sb.append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    private static LLMClient client(java.util.function.Function<String, String> reply, String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String t = text(request);
                String out = reply.apply(t);
                return LLMResponse.builder()
                        .content(out)
                        .model(model)
                        .tokenUsage(Math.max(1, t.length() / 4), Math.max(1, out.length() / 4), 0)
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    /** Searches (and checks the time for time-sensitive questions), then answers. */
    public static LLMClient agent() {
        return client(
                t -> {
                    int seen = t.split("\nObservation: ", -1).length - 1;
                    boolean agentTask = t.contains("WebSearch");
                    boolean timely = t.matches("(?is).*\\b(latest|current|today|recent|now)\\b.*");
                    if (agentTask && seen == 0) {
                        return "```json\n{\"thought\": \"verify the terms\", \"action\": \"WebSearch\","
                                + " \"action_input\": {\"query\": \"verify terms\"}}\n```";
                    }
                    if (agentTask && seen == 1 && timely) {
                        return "```json\n{\"thought\": \"check the date\", \"action\": \"CurrentDateTime\","
                                + " \"action_input\": {}}\n```";
                    }
                    String answer =
                            "[Whitepaper] I could not verify one term, so I challenge the premise. Specific"
                                    + " figures and next steps follow in two or three sentences.";
                    return agentTask ? ScriptedModel.done(answer) : answer;
                },
                "fake-agent");
    }

    /** Rates 3 to 5 depending on the text, and picks a pairwise winner the same way. */
    public static LLMClient judge() {
        return client(
                t -> {
                    int h = Math.abs(t.hashCode());
                    if (t.contains("\"winner\"")) {
                        String[] w = {"A", "B", "B", "TIE"};
                        return "```json\n{\"reasoning\": \"fake\", \"winner\": \"" + w[h % w.length] + "\"}\n```";
                    }
                    return "```json\n{\"reasoning\": \"fake verdict\", \"rating\": " + (3 + h % 3) + "}\n```";
                },
                "fake-judge");
    }
}
