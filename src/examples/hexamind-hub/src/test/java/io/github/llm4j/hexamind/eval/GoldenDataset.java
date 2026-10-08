package io.github.llm4j.hexamind.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.dataset.EvalScenarios;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Loads the golden scenarios in {@code eval/golden} and reads the conventions they use. */
public final class GoldenDataset {

    /** Where the files live, relative to the module directory (the working directory under Maven). */
    public static final Path DIR = Path.of("eval", "golden");

    public static final List<String> AGENTS = List.of("alex", "jordan", "sasha", "aris", "casey", "rahul");

    /** Dimensions the dataset may use; the report shows each of them even when nothing evaluated it. */
    public static final Set<String> DIMENSIONS =
            Set.of(
                    "fact-checking",
                    "grounding",
                    "persona-fidelity",
                    "adaptivity",
                    "reasoning",
                    "safety",
                    "efficiency",
                    "orchestration",
                    "actionability",
                    "prompting",
                    "reliability");

    /** The tool names the agents have. */
    public static final Set<String> TOOLS = Set.of("WebSearch", "CurrentDateTime");

    private GoldenDataset() {}

    public static List<EvalScenario> load(String name) {
        Path file = DIR.resolve(name.endsWith(".yaml") ? name : name + ".yaml");
        try (InputStream in = Files.newInputStream(file)) {
            return EvalScenarios.fromYaml(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
    }

    public static List<EvalScenario> agent(String agentId) {
        return load(agentId);
    }

    public static List<EvalScenario> prompts() {
        return load("prompts");
    }

    public static List<EvalScenario> workflow() {
        return load("workflow");
    }

    public static List<EvalScenario> all() {
        List<EvalScenario> out = new ArrayList<>();
        AGENTS.forEach(a -> out.addAll(agent(a)));
        out.addAll(prompts());
        out.addAll(workflow());
        return out;
    }

    /** The lines of {@code context} that start with {@code prefix}, with the prefix removed. */
    public static List<String> lines(EvalScenario s, String prefix) {
        List<String> out = new ArrayList<>();
        if (s.context() != null) {
            for (String c : s.context()) {
                if (c.startsWith(prefix)) {
                    out.add(c.substring(prefix.length()).trim());
                }
            }
        }
        return out;
    }

    /** Judged expectations: {@code RUBRIC:} lines (agents, prompts) and {@code EXPECT:} lines (workflows). */
    public static List<String> rubric(EvalScenario s) {
        List<String> out = new ArrayList<>(lines(s, "RUBRIC:"));
        out.addAll(lines(s, "EXPECT:"));
        return out;
    }

    /** The value of a tag such as {@code kind:fabricated-premise}, or null. */
    public static String tag(EvalScenario s, String key) {
        if (s.tags() != null) {
            for (String t : s.tags()) {
                if (t.startsWith(key + ":")) {
                    return t.substring(key.length() + 1);
                }
            }
        }
        return null;
    }
}
