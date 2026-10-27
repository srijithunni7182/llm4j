package io.github.llm4j.loom.review.cost;

import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The numbers an estimate rests on when a script does not say: how many items a {@code for each}
 * walks, how often a loop turns, how long an answer is. Each has a documented default, which a
 * project overrides with {@code assume.<name> = n} in {@code review.properties} and a command line
 * overrides with {@code --assume name=n}. The ones an estimate actually used are listed with it.
 */
public final class Assumptions {

    /** One assumption: its key, its default and what it means. */
    public record Def(String key, long defaultValue, String meaning) {}

    /** The table, in the order they are shown. */
    public static final List<Def> DEFS =
            List.of(
                    new Def(
                            "assume.loop.iterations",
                            3,
                            "turns of a loop until its condition holds (typical case)"),
                    new Def("assume.foreach.items", 5, "items a for each walks (typical case)"),
                    new Def(
                            "assume.foreach.max",
                            50,
                            "items a for each walks (worst case, when the list has no declared bound)"),
                    new Def(
                            "assume.output.tokens",
                            350,
                            "tokens in a model's answer when the agent sets no max_tokens"),
                    new Def("assume.output.max", 2000, "tokens in a model's answer (worst case)"),
                    new Def(
                            "assume.variable.max",
                            2000,
                            "tokens a {variable} adds to a prompt (worst case: an earlier answer at its longest)"),
                    new Def(
                            "assume.variable.tokens",
                            100,
                            "tokens a {variable} adds to a prompt when it is filled in"),
                    new Def(
                            "assume.prompt.tokens",
                            300,
                            "tokens in a prompt file when it cannot be read"),
                    new Def(
                            "assume.react.rounds",
                            3,
                            "model calls an agent with tools makes (typical case)"),
                    new Def(
                            "assume.react.max",
                            10,
                            "model calls an agent with tools makes (worst case, when it sets no max_iterations)"),
                    new Def(
                            "assume.transcript.cap",
                            8000,
                            "tokens the shared transcript is kept to where only a context block bounds it"),
                    new Def(
                            "assume.tool.result.tokens",
                            300,
                            "tokens each tool result adds to the conversation"),
                    new Def(
                            "assume.latency.small",
                            2,
                            "seconds one call of a small, fast model takes (names with haiku, mini, flash, nano, lite)"),
                    new Def(
                            "assume.latency.standard",
                            5,
                            "seconds one call of a mid-size model takes"),
                    new Def(
                            "assume.latency.large",
                            15,
                            "seconds one call of a large model takes (names with opus, fable, ultra, o1, o3, gpt-5)"),
                    new Def("assume.latency.tool", 2, "seconds one tool call or task takes"),
                    new Def(
                            "assume.effects.calls",
                            1,
                            "calls of a sending or writing tool one run of a step makes (typical case)"));

    private final Map<String, Long> values = new LinkedHashMap<>();
    private final Map<String, Long> used = new TreeMap<>();

    private Assumptions() {}

    /**
     * The defaults, then the project's, then the command line's.
     *
     * @throws IllegalArgumentException for an unknown name or a value that is not a whole number
     */
    public static Assumptions of(ReviewSettings settings) {
        Assumptions a = new Assumptions();
        for (Def d : DEFS) a.values.put(d.key(), d.defaultValue());
        List<String> problems = new ArrayList<>();
        settings.values()
                .forEach(
                        (k, v) -> {
                            if (!k.startsWith("assume.")) return;
                            if (!a.values.containsKey(k)) {
                                problems.add(
                                        "unknown assumption '"
                                                + k
                                                + "'"
                                                + Suggest.hint(k, a.values.keySet()));
                                return;
                            }
                            try {
                                long n = Long.parseLong(v.strip());
                                if (n < 0) throw new NumberFormatException();
                                a.values.put(k, n);
                            } catch (NumberFormatException e) {
                                problems.add(k + " takes a whole number, not '" + v + "'");
                            }
                        });
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        return a;
    }

    /** The value, remembered as used. */
    public long get(String key) {
        Long v = values.get(key);
        if (v == null) throw new IllegalArgumentException("no assumption " + key);
        used.put(key, v);
        return v;
    }

    /** The assumptions that were read, with their values, for the report. */
    public Map<String, Long> used() {
        return new TreeMap<>(used);
    }

    public static Def def(String key) {
        return DEFS.stream().filter(d -> d.key().equals(key)).findFirst().orElseThrow();
    }
}
