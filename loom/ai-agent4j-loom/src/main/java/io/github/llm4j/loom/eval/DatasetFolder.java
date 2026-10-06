package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalDataset;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A script's golden dataset, matched to what it can evaluate. {@code researcher.yaml} is for the agent {@code Researcher} (case does not matter);
 * {@code Diagnose.yaml} is for the workflow {@code Diagnose}; {@code workflow.yaml} is for the workflow named with {@code --workflow}, else
 * {@code Main}, else the script's only workflow. A file that is none of these is reported with the names nearest to it.
 */
public final class DatasetFolder {

    /** A target and the scenarios written for it. */
    public record Target(String kind, String name, String file, List<EvalScenario> scenarios) {
        public boolean isAgent() {
            return kind.equals("agent");
        }
    }

    /** The matched dataset: what to run, and what is wrong. */
    public record Plan(Path dir, List<Target> targets, List<String> problems, EvalDataset dataset) {
        public boolean ok() {
            return problems.isEmpty();
        }

        public int scenarioCount() {
            return targets.stream().mapToInt(t -> t.scenarios().size()).sum();
        }
    }

    private DatasetFolder() {}

    /** {@code eval/golden} beside the script, unless a folder is given. */
    public static Path locate(Path scriptFile, Path given) {
        if (given != null) return given.toAbsolutePath().normalize();
        Path base = scriptFile.toAbsolutePath().getParent();
        return base.resolve("eval").resolve("golden").normalize();
    }

    /** Reads the folder and matches its files to the script. {@code workflowChoice} (may be null) says which workflow {@code workflow.yaml} is for. */
    public static Plan plan(LoomScript script, Path dir, String workflowChoice) {
        EvalDataset dataset = EvalDataset.load(dir);
        List<String> problems = new ArrayList<>();
        dataset.problems().forEach(p -> problems.add(p.toString()));
        List<Target> targets = new ArrayList<>();
        List<String> agents = script.getAgents().stream().map(AgentDef::getName).toList();
        List<String> workflows = script.getWorkflows().stream().map(WorkflowDef::getName).toList();
        for (Map.Entry<String, List<EvalScenario>> file : dataset.files().entrySet()) {
            String stem = file.getKey();
            String agent = named(agents, stem);
            String workflow = named(workflows, stem);
            if (agent != null) {
                targets.add(new Target("agent", agent, stem + ".yaml", file.getValue()));
            } else if (workflow != null) {
                targets.add(new Target("workflow", workflow, stem + ".yaml", file.getValue()));
            } else if (stem.equalsIgnoreCase("workflow")) {
                String chosen = chooseWorkflow(workflows, workflowChoice);
                if (chosen == null) {
                    problems.add(stem + ".yaml: it is not clear which workflow this is for (" + String.join(", ", workflows)
                            + "); name the file after the workflow, for example " + (workflows.isEmpty() ? "Main" : workflows.get(0)) + ".yaml, or use --workflow");
                } else {
                    targets.add(new Target("workflow", chosen, stem + ".yaml", file.getValue()));
                }
            } else {
                List<String> all = new ArrayList<>(agents);
                all.addAll(workflows);
                List<String> near = PromptCatalog.nearest(stem, all);
                problems.add(stem + ".yaml: the script has no agent or workflow named " + stem
                        + (near.isEmpty() ? " (it has " + String.join(", ", all) + ")" : "; did you mean " + String.join(", ", near) + "?"));
            }
        }
        return new Plan(dir, targets, problems, dataset);
    }

    private static String named(List<String> names, String stem) {
        for (String n : names) if (n.equalsIgnoreCase(stem)) return n;
        return null;
    }

    private static String chooseWorkflow(List<String> workflows, String choice) {
        if (choice != null) return named(workflows, choice);
        String main = named(workflows, "Main");
        if (main != null) return main;
        return workflows.size() == 1 ? workflows.get(0) : null;
    }

    /** Whether the folder has any dataset file at all. */
    public static boolean exists(Path dir) {
        return Files.isDirectory(dir);
    }
}
