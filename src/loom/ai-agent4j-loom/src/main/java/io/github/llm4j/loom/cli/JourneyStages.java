package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.eval.DatasetIdentity;
import io.github.llm4j.loom.eval.DatasetFacts;
import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The eleven stages of the journey from an idea to a watched deployment, each with a state read
 * from the project's files and the results recorded in it, the evidence that state came from, and
 * the person's skips. Nothing here calls a model or asks a person, so the editor, the skills and the
 * Guide can read it as often as they like.
 */
final class JourneyStages {

    static final String DONE = "done";
    static final String FAILING = "failing";
    static final String NOT_STARTED = "not-started";
    static final String SKIPPED = "skipped";
    static final String STALE = "stale";

    /**
     * One stage: a stable id, a title, its state, why, the command that moves it on (or null for an
     * edit), and the evidence its state was read from.
     */
    record Stage(int number, String id, String title, String state, String detail, String command, List<String> evidence) {
        Stage {
            evidence = List.copyOf(evidence);
        }

        Stage(int number, String id, String title, String state, String detail, String command) {
            this(number, id, title, state, detail, command, List.of());
        }

        Stage numbered(int n) {
            return new Stage(n, id, title, state, detail, command, evidence);
        }

        Stage withEvidence(List<String> e) {
            return new Stage(number, id, title, state, detail, command, e);
        }

        Stage withState(String newState, String newDetail) {
            return new Stage(number, id, title, newState, newDetail, command, evidence);
        }
    }

    /** The stages with the person's skips applied, the skips themselves, and what could not be read. */
    record Journey(List<Stage> stages, java.util.Map<String, SkippedStages.Skip> skips, List<String> warnings) {
        Journey {
            stages = List.copyOf(stages);
            skips = java.util.Map.copyOf(skips);
            warnings = List.copyOf(warnings);
        }
    }

    /** The stage ids, in order: what {@code weave next --skip} accepts. */
    static final List<String> IDS =
            List.of(
                    "decide-agents", "design-harness", "design-evaluation", "golden-dataset", "script",
                    "audit-and-review", "scripted-tests", "preflight", "real-run-read", "real-evaluation", "deploy");

    /** The three stages the README line {@code Evaluation: skipped} stands for. */
    static final List<String> EVALUATION_STAGES = List.of("design-evaluation", "golden-dataset", "real-evaluation");

    private JourneyStages() {}

    static List<Stage> of(
            Path script, LoomScript loaded, ProjectAdvisor.CheckResult check, AuditReport audit) {
        return journey(script, loaded, check, audit).stages();
    }

    /** The journey with its evidence, the recorded skips and any warning about reading them. */
    static Journey journey(
            Path script, LoomScript loaded, ProjectAdvisor.CheckResult check, AuditReport audit) {
        Path root = io.github.llm4j.loom.init.ProjectLayout.root(script);
        SkippedStages.Read skips = SkippedStages.read(root);
        List<Stage> out = new ArrayList<>();
        int n = 0;
        for (Stage st : computed(script, loaded, check, audit)) {
            Stage s = st.numbered(++n).withEvidence(evidence(st.id(), root, script, loaded, check, audit));
            SkippedStages.Skip skip = skips.skips().get(s.id());
            if (skip != null && (s.state().equals(NOT_STARTED) || s.state().equals(STALE)))
                s = s.withState(SKIPPED, "skipped: " + skip.because());
            out.add(s);
        }
        return new Journey(out, skips.skips(), skips.warning().map(List::of).orElse(List.of()));
    }

    private static List<Stage> computed(
            Path script, LoomScript loaded, ProjectAdvisor.CheckResult check, AuditReport audit) {
        Path root = io.github.llm4j.loom.init.ProjectLayout.root(script);
        String file = io.github.llm4j.loom.init.ProjectLayout.shown(script);
        boolean skippedEvaluation = readme(root).contains("Evaluation: skipped");
        Path golden = io.github.llm4j.loom.init.ProjectLayout.dataset(script);
        DatasetFacts dataset = DatasetFacts.of(golden);
        boolean hasDataset = dataset.present();
        boolean scriptValid = check.loaded() && check.errors().isEmpty();
        PreflightState preflight = PreflightState.read(root, script);

        List<Stage> stages = new ArrayList<>();
        stages.add(
                loaded != null && !loaded.getAgents().isEmpty()
                        ? stage(1, "decide-agents", "Decide your agents", DONE, loaded.getAgents().size() + " agent(s) in the script", null)
                        : stage(1, "decide-agents", "Decide your agents", NOT_STARTED, "the script has no agent yet", null));
        stages.add(
                Files.isRegularFile(root.resolve("HARNESS.md"))
                        ? stage(0, "design-harness", "Design the harness", DONE, "HARNESS.md", null)
                        : stage(0, "design-harness", "Design the harness", NOT_STARTED, HarnessShape.advice(loaded), null));
        stages.add(designStage(root, skippedEvaluation));
        stages.add(datasetStage(file, dataset, skippedEvaluation));
        stages.add(scriptStage(file, check, scriptValid));
        stages.add(auditStage(file, audit));
        stages.add(
                hasTests(root)
                        ? stage(6, "scripted-tests", "Scripted tests", DONE, "test files exist (run `weave test` to see them pass)", "weave test " + file + " --coverage")
                        : stage(6, "scripted-tests", "Scripted tests", NOT_STARTED, "no tests/*.test.yaml yet", "weave test " + file + " --coverage"));
        stages.add(preflightStage(file, preflight));
        stages.add(realRunStage(root, script, preflight));
        stages.add(evaluationStage(root, script, file, golden, hasDataset, skippedEvaluation));
        stages.add(deployStage(root, file));
        return List.copyOf(stages);
    }

    /**
     * The step to take now: a stage that is failing comes first (nothing built on it can be
     * trusted), otherwise the first stage that is neither done nor skipped. Empty when the journey
     * is complete.
     */
    static Optional<Stage> primary(List<Stage> stages) {
        Optional<Stage> failing = stages.stream().filter(s -> s.state().equals(FAILING)).findFirst();
        if (failing.isPresent()) return failing;
        return stages.stream().filter(s -> !s.state().equals(DONE) && !s.state().equals(SKIPPED)).findFirst();
    }

    private static Stage stage(int number, String id, String title, String state, String detail, String command) {
        return new Stage(number, id, title, state, detail, command);
    }

    /** Where a stage's state was read from, in words a person can check for themselves. */
    private static List<String> evidence(
            String id, Path root, Path script, LoomScript loaded, ProjectAdvisor.CheckResult check, AuditReport audit) {
        String file = io.github.llm4j.loom.init.ProjectLayout.shown(script);
        List<String> e = new ArrayList<>(
                switch (id) {
                    case "decide-agents" -> List.of(file + ": " + (loaded == null ? "could not be read" : loaded.getAgents().size() + " agent(s)"));
                    case "design-harness" -> List.of(present(root, "HARNESS.md"));
                    case "design-evaluation" -> List.of(present(root, "EVAL_DESIGN.md"));
                    case "golden-dataset" -> List.of(present(root, root.relativize(io.github.llm4j.loom.init.ProjectLayout.dataset(script)).toString()));
                    case "script" -> List.of("weave check: " + check.errors().size() + " error(s), " + check.warnings().size() + " warning(s)");
                    case "audit-and-review" -> List.of(audit == null
                            ? "weave audit: not run (the script could not be read)"
                            : "weave audit: " + audit.count(Severity.HIGH) + " high, " + audit.count(Severity.MEDIUM) + " medium, " + audit.count(Severity.LOW) + " low");
                    case "scripted-tests" -> List.of("tests/*.test.yaml: " + (hasTests(root) ? "present" : "absent"));
                    case "preflight" -> List.of(".loom/preflight.json: " + PreflightState.read(root, script).state());
                    case "real-run-read" -> List.of(".loom/confirmed.json: " + (RealRunRead.confirmed(root, script) ? "a real answer was read" : "no confirmation"));
                    case "real-evaluation" -> List.of(".loom/eval.json: " + EvalRecord.read(root).map(r -> "last evaluation " + r.mode()).orElse("absent"));
                    case "deploy" -> List.of(present(root, "deploy"));
                    default -> List.of();
                });
        if (EVALUATION_STAGES.contains(id) && readme(root).contains("Evaluation: skipped")) e.add("README.md: Evaluation: skipped");
        return e;
    }

    private static String present(Path root, String path) {
        return path + ": " + (Files.exists(root.resolve(path)) ? "present" : "absent");
    }

    private static Stage designStage(Path root, boolean skipped) {
        if (Files.isRegularFile(root.resolve("EVAL_DESIGN.md")))
            return stage(2, "design-evaluation", "Design the evaluation", DONE, "EVAL_DESIGN.md", null);
        if (skipped) return stage(2, "design-evaluation", "Design the evaluation", SKIPPED, "README says: Evaluation: skipped", null);
        return stage(2, "design-evaluation", "Design the evaluation", NOT_STARTED, "write EVAL_DESIGN.md with the evaluation-design skill", null);
    }

    private static Stage datasetStage(String file, DatasetFacts dataset, boolean skipped) {
        String title = "Requirements (golden dataset)";
        if (dataset.present()) {
            if (dataset.firstProblem() != null)
                return stage(3, "golden-dataset", title, FAILING, dataset.firstProblem(), "weave eval " + file + " --check");
            long drafted = dataset.unreviewed();
            return stage(3, "golden-dataset", title, DONE, drafted > 0 ? drafted + " drafted case(s) no person has reviewed" : "reviewed", "weave eval " + file + " --check");
        }
        if (skipped) return stage(3, "golden-dataset", title, SKIPPED, "README says: Evaluation: skipped", null);
        return stage(3, "golden-dataset", title, NOT_STARTED, "no eval/golden yet", "weave eval " + file + " --init");
    }

    private static Stage scriptStage(String file, ProjectAdvisor.CheckResult check, boolean valid) {
        String title = "The script checks clean";
        String command = "weave check " + file + " --no-env";
        if (!valid)
            return stage(4, "script", title, FAILING, check.errors().isEmpty() ? "the script could not be read" : check.errors().get(0), command);
        return stage(4, "script", title, DONE, check.warnings().isEmpty() ? "no warnings" : check.warnings().size() + " warning(s)", command);
    }

    private static Stage auditStage(String file, AuditReport audit) {
        String title = "Audit and review";
        long high = audit == null ? 0 : audit.count(Severity.HIGH);
        if (high > 0)
            return stage(5, "audit-and-review", title, FAILING, high + " high finding(s) in the audit", "weave audit " + file + " --fail-on high");
        return stage(5, "audit-and-review", title, DONE, "no high finding in the audit (the review is read with `weave review`)", "weave review " + file);
    }

    private static Stage preflightStage(String file, PreflightState p) {
        String title = "Preflight: one real answer, checked";
        String command = "weave preflight " + file;
        return switch (p.state()) {
            case PreflightState.DONE -> stage(7, "preflight", title, DONE, "passed " + nullToEmpty(p.at()), command);
            case PreflightState.FAILING -> stage(7, "preflight", title, FAILING, p.detail(), command);
            case PreflightState.STALE -> stage(7, "preflight", title, STALE, "the script changed since the last preflight", command);
            case PreflightState.MOCK_ONLY -> stage(7, "preflight", title, NOT_STARTED, "only a mock preflight so far", command);
            default -> stage(7, "preflight", title, NOT_STARTED, "not run yet", command);
        };
    }

    private static Stage realRunStage(Path root, Path script, PreflightState preflight) {
        String title = "Read one real answer";
        if (RealRunRead.confirmed(root, script))
            return stage(8, "real-run-read", title, DONE, "a person read one real answer of this script", null);
        String detail = preflight.state().equals(PreflightState.DONE) ? "read the answer, say what is right and wrong, then confirm" : "needs a passing preflight first";
        return stage(8, "real-run-read", title, NOT_STARTED, detail, "weave next --confirm real-run");
    }

    private static Stage evaluationStage(Path root, Path script, String file, Path golden, boolean hasDataset, boolean skipped) {
        String title = "Real evaluation";
        String command = "weave eval " + file + " --max-tokens 200000";
        if (skipped) return stage(9, "real-evaluation", title, SKIPPED, "README says: Evaluation: skipped", null);
        Optional<EvalRecord> last = EvalRecord.read(root);
        if (last.isEmpty() || !last.get().mode().equals(EvalRecord.REAL))
            return stage(9, "real-evaluation", title, NOT_STARTED, last.isPresent() ? "only a mock evaluation so far" : "not run yet", command);
        try {
            boolean sameScript = last.get().scriptHash().equals(PreflightCommand.hash(Files.readString(script)));
            boolean sameDataset = !hasDataset || last.get().datasetVersion().equals(DatasetIdentity.of(golden).describe());
            if (sameScript && sameDataset) return stage(9, "real-evaluation", title, DONE, "a real evaluation of this script and dataset", command);
            return stage(9, "real-evaluation", title, STALE, sameScript ? "the dataset changed since the last real evaluation" : "the script changed since the last real evaluation", command);
        } catch (IOException e) {
            return stage(9, "real-evaluation", title, NOT_STARTED, "the script could not be read", command);
        }
    }

    private static Stage deployStage(Path root, String file) {
        String title = "Deploy and watch";
        Path deploy = root.resolve("deploy");
        boolean written = false;
        if (Files.isDirectory(deploy))
            try (Stream<Path> s = Files.list(deploy)) {
                written = s.findAny().isPresent();
            } catch (IOException e) {
                written = false;
            }
        return written
                ? stage(10, "deploy", title, DONE, "deployment files in deploy/", "weave deploy " + file + " --verify")
                : stage(10, "deploy", title, NOT_STARTED, "no deployment files yet", "weave deploy " + file + " --dry-run");
    }

    private static boolean hasTests(Path dir) {
        for (String d : new String[] {"tests", "src/test/resources/tests"}) {
            Path t = dir.resolve(d);
            if (Files.isDirectory(t))
                try (Stream<Path> s = Files.walk(t, 2)) {
                    if (s.anyMatch(p -> p.getFileName().toString().endsWith(".test.yaml"))) return true;
                } catch (IOException e) {
                    return false;
                }
        }
        return false;
    }

    private static String readme(Path dir) {
        try {
            Path r = dir.resolve("README.md");
            return Files.isRegularFile(r) ? Files.readString(r) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
