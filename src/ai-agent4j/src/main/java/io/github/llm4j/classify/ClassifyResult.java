package io.github.llm4j.classify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answers to a {@link ClassifyRequest}, one per question and no others, and what the call cost.
 *
 * @param answers by question name, in the request's order
 * @param usage tokens, latency, backend and model
 */
public record ClassifyResult(Map<String, Answer> answers, ClassifyUsage usage) {

    public ClassifyResult {
        answers =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(answers == null ? Map.of() : answers));
        if (usage == null) throw new IllegalArgumentException("a result needs its usage");
    }

    public Answer answer(String name) {
        Answer a = answers.get(name);
        if (a == null)
            throw new IllegalArgumentException(
                    "no answer named '" + name + "'; answered: " + answers.keySet());
        return a;
    }

    public ChoiceAnswer choice(String name) {
        return as(name, ChoiceAnswer.class, "a choice");
    }

    public YesNoAnswer yesNo(String name) {
        return as(name, YesNoAnswer.class, "a yes/no");
    }

    public ScoreAnswer score(String name) {
        return as(name, ScoreAnswer.class, "a score");
    }

    private <T extends Answer> T as(String name, Class<T> type, String what) {
        Answer a = answer(name);
        if (!type.isInstance(a))
            throw new IllegalArgumentException("'" + name + "' is not " + what + " answer");
        return type.cast(a);
    }
}
