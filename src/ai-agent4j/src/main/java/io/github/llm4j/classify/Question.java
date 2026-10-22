package io.github.llm4j.classify;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One typed ask about the state of a {@link ClassifyRequest}. Questions are named; the answer comes
 * back under the same name.
 */
public sealed interface Question permits Question.Choice, Question.YesNo, Question.Score {

    /**
     * What a question or state field may be called: letters, digits, {@code _ . -}, 1 to 64
     * characters.
     */
    Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** Most labels a {@link Choice} may have (Jev's limit). */
    int MAX_OPTIONS = 255;

    String name();

    /** What to decide, in the developer's words; sent to the backend as written. */
    String instructions();

    static void check(String name, String instructions) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "a question name is 1 to 64 letters, digits, '_', '.' or '-', got: " + name);
        }
        if (instructions == null || instructions.isBlank())
            throw new IllegalArgumentException("question " + name + " needs instructions");
    }

    /** Pick exactly one of the options. */
    record Choice(String name, String instructions, List<Option> options) implements Question {

        public Choice {
            check(name, instructions);
            options = options == null ? List.of() : List.copyOf(options);
            if (options.size() < 2)
                throw new IllegalArgumentException(
                        "question " + name + " needs at least two labels, got " + options.size());
            if (options.size() > MAX_OPTIONS)
                throw new IllegalArgumentException(
                        "question " + name + " has more than " + MAX_OPTIONS + " labels");
            if (new HashSet<>(labels(options)).size() != options.size()) {
                throw new IllegalArgumentException(
                        "question " + name + " has duplicate labels: " + labels(options));
            }
        }

        public static Choice of(String name, String instructions, String... labels) {
            return new Choice(
                    name, instructions, java.util.Arrays.stream(labels).map(Option::of).toList());
        }

        public List<String> labels() {
            return labels(options);
        }

        private static List<String> labels(List<Option> options) {
            return options.stream().map(Option::label).toList();
        }
    }

    /** Yes or no; the answer is the probability of yes. */
    record YesNo(String name, String instructions) implements Question {

        public YesNo {
            check(name, instructions);
        }
    }

    /** A point on an ordered {@link Scale}; the answer is a position along it. */
    record Score(String name, String instructions, Scale scale) implements Question {

        public Score {
            check(name, instructions);
            if (scale == null)
                throw new IllegalArgumentException("question " + name + " needs a scale");
        }
    }
}
