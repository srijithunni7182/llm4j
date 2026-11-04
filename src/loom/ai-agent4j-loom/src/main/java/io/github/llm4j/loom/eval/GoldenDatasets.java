package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalDataset;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The one place that answers "which agents and nodes have golden cases?", so that {@code weave
 * eval}, review rule LT06, the "no evaluation" notice and {@code weave next} can never disagree
 * about whether a dataset exists (docs/specs/28-evaluation-first, R1 and R2).
 *
 * <p>A file {@code researcher.yaml} (any capitalisation, {@code .yaml} or {@code .yml}) holds the
 * cases of the agent or node {@code Researcher}. A workflow's file ({@code workflow.yaml}, or named
 * after the workflow) also covers every agent its cases name in an {@code expect} line.
 */
public final class GoldenDatasets {

    /** What a dataset folder covers. */
    public record Coverage(Set<String> covered, boolean anyCases) {
        /** Whether the named agent or node has at least one case, of its own or by a workflow. */
        public boolean covers(String name) {
            return covered.stream().anyMatch(c -> c.equalsIgnoreCase(name));
        }
    }

    private static final String SKIPPED_MARK = "Evaluation: skipped";

    private GoldenDatasets() {}

    /** The file of the cases of {@code name} in the folder, matched without regard to case. */
    public static Optional<Path> find(Path dir, String name) {
        if (dir == null || !Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(
                            f -> {
                                String file = f.getFileName().toString().toLowerCase(Locale.ROOT);
                                return file.equals(name.toLowerCase(Locale.ROOT) + ".yaml")
                                        || file.equals(name.toLowerCase(Locale.ROOT) + ".yml");
                            })
                    .sorted()
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** What the folder covers for this script; nothing when there is no folder. */
    public static Coverage coverage(LoomScript script, Path dir) {
        Set<String> covered = new LinkedHashSet<>();
        boolean any = false;
        if (dir != null && Files.isDirectory(dir)) {
            EvalDataset dataset = EvalDataset.load(dir);
            List<String> workflows = script.getWorkflows().stream().map(WorkflowDef::getName).toList();
            for (Map.Entry<String, List<EvalScenario>> file : dataset.files().entrySet()) {
                List<EvalScenario> cases = file.getValue();
                if (cases.isEmpty()) continue;
                any = true;
                String stem = file.getKey();
                covered.add(stem);
                boolean workflowFile =
                        stem.equalsIgnoreCase("workflow")
                                || workflows.stream().anyMatch(w -> w.equalsIgnoreCase(stem));
                if (workflowFile) covered.addAll(namedInExpectations(script, cases));
            }
        }
        return new Coverage(covered, any);
    }

    /** Every agent, extractor and evaluator of the script, in the order they are declared. */
    public static List<String> testableNames(LoomScript script) {
        Set<String> names = new LinkedHashSet<>();
        for (AgentDef a : script.getAgents()) names.add(a.getName());
        for (ExtractorDef x : script.getExtractors()) names.add(x.getName());
        for (EvaluatorDef e : script.getEvaluators()) names.add(e.getName());
        return List.copyOf(names);
    }

    /** True when the project's README records that evaluation was skipped on purpose. */
    public static boolean skippedOnPurpose(Path projectRoot) {
        try {
            Path readme = projectRoot.resolve("README.md");
            return Files.isRegularFile(readme) && Files.readString(readme).contains(SKIPPED_MARK);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The line {@code weave run} and {@code weave deploy} print when nothing measures the agents;
     * null when the script has nothing to measure or already has cases.
     */
    public static String notice(LoomScript script, Path scriptFile) {
        if (testableNames(script).isEmpty()) return null;
        Path dir = DatasetFolder.locate(scriptFile, null);
        if (coverage(script, dir).anyCases()) return null;
        if (skippedOnPurpose(io.github.llm4j.loom.init.ProjectLayout.root(scriptFile)))
            return "Evaluation was skipped on purpose (see README).";
        return "No evaluation: nothing has measured whether these agents are right."
                + " `weave eval --init` starts one.";
    }

    private static Set<String> namedInExpectations(LoomScript script, List<EvalScenario> cases) {
        Set<String> named = new LinkedHashSet<>();
        for (String name : testableNames(script)) {
            Pattern word = Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "(?![\\w])");
            for (EvalScenario c : cases) {
                if (c.expect() != null && c.expect().stream().anyMatch(e -> word.matcher(e).find())) {
                    named.add(name);
                    break;
                }
            }
        }
        return named;
    }
}
