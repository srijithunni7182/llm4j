package io.github.llm4j.classify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What to classify and what to ask about it. A request that breaks the rules (no questions, a
 * duplicate name, fewer than two labels, a state over {@link #MAX_STATE_CHARS}) cannot be built, so
 * no backend is ever called with one.
 *
 * @param state the thing being classified: one unnamed text, or an ordered map of named text fields
 *     ({@code message}, {@code history}, …)
 * @param questions one or more questions with unique names; the backend answers them all in one
 *     call
 */
public record ClassifyRequest(Map<String, String> state, List<Question> questions) {

    /** Longest state accepted, in characters over all fields. A longer one is rejected, not cut. */
    public static final int MAX_STATE_CHARS = 100_000;

    /** The key of a state given as one unnamed text. */
    public static final String TEXT = "";

    public ClassifyRequest {
        if (state == null || state.isEmpty())
            throw new IllegalArgumentException("a classify request needs a state");
        state = Collections.unmodifiableMap(new LinkedHashMap<>(state));
        long chars = 0;
        for (Map.Entry<String, String> e : state.entrySet()) {
            if (e.getValue() == null)
                throw new IllegalArgumentException("state field '" + e.getKey() + "' is null");
            boolean plain = e.getKey().equals(TEXT);
            if (plain ? state.size() != 1 : !Question.NAME.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException(
                        "a state field name is 1 to 64 letters, digits, '_', '.' or '-' (an unnamed text must be the only field), got: '"
                                + e.getKey()
                                + "'");
            }
            chars += e.getValue().length();
        }
        if (chars > MAX_STATE_CHARS) {
            throw new IllegalArgumentException(
                    "the state is "
                            + chars
                            + " characters, over the limit of "
                            + MAX_STATE_CHARS
                            + "; shorten it");
        }
        if (questions == null || questions.isEmpty())
            throw new IllegalArgumentException("a classify request needs at least one question");
        questions = List.copyOf(questions);
        Set<String> names = new HashSet<>();
        for (Question q : questions) {
            if (!names.add(q.name()))
                throw new IllegalArgumentException("duplicate question name: " + q.name());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The question with this name, if there is one. */
    public Optional<Question> question(String name) {
        return questions.stream().filter(q -> q.name().equals(name)).findFirst();
    }

    /** The state as one text, when it was given as one unnamed text. */
    public Optional<String> plainText() {
        return Optional.ofNullable(state.get(TEXT));
    }

    public static final class Builder {

        private final Map<String, String> state = new LinkedHashMap<>();
        private final List<Question> questions = new ArrayList<>();

        private Builder() {}

        /** The state as one unnamed text. */
        public Builder state(String text) {
            state.put(TEXT, text);
            return this;
        }

        /** Adds a named field to the state. */
        public Builder state(String name, String value) {
            state.put(name, value);
            return this;
        }

        public Builder state(Map<String, String> fields) {
            state.putAll(fields);
            return this;
        }

        public Builder question(Question question) {
            questions.add(question);
            return this;
        }

        public Builder choice(String name, String instructions, String... labels) {
            return question(Question.Choice.of(name, instructions, labels));
        }

        public Builder choice(String name, String instructions, List<Option> options) {
            return question(new Question.Choice(name, instructions, options));
        }

        public Builder yesNo(String name, String instructions) {
            return question(new Question.YesNo(name, instructions));
        }

        public Builder score(String name, String instructions, Scale scale) {
            return question(new Question.Score(name, instructions, scale));
        }

        public ClassifyRequest build() {
            return new ClassifyRequest(state, questions);
        }
    }
}
