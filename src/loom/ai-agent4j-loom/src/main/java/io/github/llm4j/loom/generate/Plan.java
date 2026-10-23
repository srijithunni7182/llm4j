package io.github.llm4j.loom.generate;

import java.util.List;

/**
 * What will be built, as a person can read and edit it before any script is written: the agents,
 * classifiers, tools (with what they reach), the people asked, the effects and who approves them,
 * the budget, how failure is handled and the tests that will be written.
 *
 * @param answers what the interview settled (or the defaults it assumed), one line each
 */
public record Plan(
        int version,
        String name,
        String summary,
        List<Answer> answers,
        List<Agent> agents,
        List<Classifier> classifiers,
        List<Tool> tools,
        List<Human> humans,
        List<Effect> effects,
        Budget budget,
        String failure,
        List<TestIdea> tests,
        boolean personalData) {

    public static final int VERSION = 1;

    public Plan {
        answers = answers == null ? List.of() : List.copyOf(answers);
        agents = agents == null ? List.of() : List.copyOf(agents);
        classifiers = classifiers == null ? List.of() : List.copyOf(classifiers);
        tools = tools == null ? List.of() : List.copyOf(tools);
        humans = humans == null ? List.of() : List.copyOf(humans);
        effects = effects == null ? List.of() : List.copyOf(effects);
        tests = tests == null ? List.of() : List.copyOf(tests);
    }

    /** A question of the interview, the answer given, and whether it is only the default. */
    public record Answer(String category, String question, String answer, boolean defaulted) {}

    /**
     * @param prompt the stem of the prompt file ({@code replier} for {@code prompts/replier.md})
     */
    public record Agent(String name, String model, String role, List<String> tools, String prompt) {
        public Agent {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /**
     * @param backend {@code jev} or {@code chat}
     */
    public record Classifier(
            String name, String backend, String role, List<String> questions, String onUnsure) {
        public Classifier {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    /**
     * @param kind a built-in kind ({@code http}, {@code email}, ...) or {@code class} for a stub
     * @param reach {@code none}, {@code reads}, {@code fetches}, {@code writes} or {@code sends}
     * @param stub the developer writes the tool; a failing test says what to implement
     */
    public record Tool(String name, String kind, String reach, boolean stub, String reason) {}

    public record Human(String name, String when) {}

    /**
     * An effect and who approves it.
     *
     * @param approval {@code required} or {@code none}; {@code none} needs a {@code because}
     */
    public record Effect(String tool, String reach, String approval, String because) {}

    public record Budget(Long tokens, Integer calls) {}

    public record TestIdea(String name, String what) {}
}
