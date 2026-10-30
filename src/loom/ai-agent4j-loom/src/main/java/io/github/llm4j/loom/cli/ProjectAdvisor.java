package io.github.llm4j.loom.cli;

import io.github.llm4j.eval.dataset.EvalDataset;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What to do next in a project, from the files in it and the free checks (the script's own check,
 * the dataset check, the audit). It never calls a model and never reads a key. The steps come in
 * the order the guide works: make it valid, decide about tests, cover every agent, limit it, review
 * its reach, prove the wiring for free, and only then spend.
 */
final class ProjectAdvisor {

    /** One thing to do: what, why, and the command to run (null when it is an edit). */
    record Step(String what, String why, String command) {}

    /** What the script's own check found. */
    record CheckResult(
            boolean loaded, List<String> errors, List<String> warnings, Set<String> keysNotSet) {}

    private ProjectAdvisor() {}

    static List<Step> advise(Path script, LoomScript loaded, CheckResult check, AuditReport audit) {
        List<Step> steps = new ArrayList<>();
        String file = io.github.llm4j.loom.init.ProjectLayout.shown(script);
        Path dir = io.github.llm4j.loom.init.ProjectLayout.root(script);

        if (!check.loaded() || !check.errors().isEmpty()) {
            steps.add(
                    new Step(
                            "Fix what stops the script from being valid: "
                                    + first(check.errors(), 2),
                            "Nothing else works until the script checks clean.",
                            "weave check " + file + " --no-env"));
            return steps;
        }
        if (!check.warnings().isEmpty()) {
            steps.add(
                    new Step(
                            "Look at the "
                                    + check.warnings().size()
                                    + " warning"
                                    + (check.warnings().size() == 1 ? "" : "s")
                                    + " from the check: "
                                    + first(check.warnings(), 2),
                            "A warning is usually a real mistake in the workflow (a result never used, a name that is also a word in a prompt).",
                            "weave check " + file + " --no-env --strict"));
        }

        java.util.List<String> keysInTemplate = EnvExampleCheck.namesWithValues(script);
        if (!keysInTemplate.isEmpty()) {
            steps.add(
                    new Step(
                            "Move the key out of the template: it holds a value for "
                                    + String.join(", ", keysInTemplate),
                            "A template like .env.example gets committed, so a key in it is shared with everyone who sees the project. The secret store keeps it encrypted and bound to the hosts it may be sent to.",
                            "weave secrets import-env --from .env.example --secrets keys.store        (it offers to blank the template; create the store first with weave secrets create)"));
        }

        Path golden = io.github.llm4j.loom.init.ProjectLayout.dataset(script);
        boolean skipped = readme(dir).contains("Evaluation: skipped");
        EvalDataset dataset = Files.isDirectory(golden) ? EvalDataset.load(golden) : null;
        boolean hasDataset = dataset != null && !dataset.files().isEmpty();

        if (!hasDataset && !skipped) {
            steps.add(
                    new Step(
                            "Decide about tests: write a golden dataset (example requests and what a good answer does), or record that you are skipping evaluation",
                            "Evaluation is optional, but without it nothing says whether the workflow is any good, only that it runs.",
                            "weave eval "
                                    + file
                                    + " --init        (or write 'Evaluation: skipped' in README.md)"));
        }
        if (hasDataset) {
            List<String> fatal =
                    dataset.problems().stream().map(EvalDataset.Problem::toString).toList();
            if (!fatal.isEmpty()) {
                steps.add(
                        new Step(
                                "Fix the golden dataset: " + first(fatal, 2),
                                "A dataset with problems is not run.",
                                "weave eval " + file + " --check"));
            }
            Set<String> covered =
                    dataset.files().keySet().stream()
                            .map(s -> s.toLowerCase(Locale.ROOT))
                            .collect(Collectors.toSet());
            List<String> bare =
                    loaded.getAgents().stream()
                            .map(AgentDef::getName)
                            .filter(n -> !covered.contains(n.toLowerCase(Locale.ROOT)))
                            .toList();
            if (!bare.isEmpty()) {
                steps.add(
                        new Step(
                                "Add cases for the agent"
                                        + (bare.size() == 1 ? "" : "s")
                                        + " with none: "
                                        + String.join(", ", bare)
                                        + " (include one hostile input and one question about something that does not exist)",
                                "An agent with no cases is an agent nobody has tested.",
                                "weave eval "
                                        + file
                                        + " --init        (adds a starter file for each agent that has none; never overwrites)"));
            }
        }

        steps.add(
                new Step(
                        "Read the review: what could go wrong, with the fix for each",
                        "It is free and quick, and it finds loops without limits, unguarded personal data and tools that can act without a person (advice, never a gate).",
                        "weave review " + file));
        if (hasTests(dir)) {
            steps.add(
                    new Step(
                            "Run the tests and see what they leave uncovered",
                            "They run on scripted models, so they are free, instant and the same every time.",
                            "weave test " + file + " --coverage"));
        } else {
            steps.add(
                    new Step(
                            "Write tests for the branches and the limits (tests/main.test.yaml)",
                            "A test with scripted models says a branch does what you meant, for nothing. weave init templates come with some to copy.",
                            "weave test " + file + " --coverage"));
        }

        if (loaded.getBudget() == null) {
            steps.add(
                    new Step(
                            "Set a budget at the top of the script, for example: budget { tokens: 200000  calls: 30 }",
                            "A real run should have a limit of its own before it spends anything.",
                            null));
        }
        long reach = audit.count(Severity.HIGH) + audit.count(Severity.MEDIUM);
        if (reach > 0) {
            steps.add(
                    new Step(
                            "Review the "
                                    + reach
                                    + " high or medium finding"
                                    + (reach == 1 ? "" : "s")
                                    + " in the security audit",
                            "It lists what each agent can reach and where a person should approve first.",
                            "weave audit " + file + " --fail-on medium"));
        }
        if (hasDataset) {
            steps.add(
                    new Step(
                            "Run the whole dataset on mock models to prove the wiring",
                            "Free: every step gets a fixed reply, and a code step that changes things is described, not run. It checks the connections, not the quality (content checks come out unjudged).",
                            "weave eval " + file + " --mock"));
        }
        if (!check.keysNotSet().isEmpty()) {
            String names = String.join(", ", check.keysNotSet());
            boolean example = Files.isRegularFile(dir.resolve(".env.example"));
            boolean envThere = Files.isRegularFile(dir.resolve(".env"));
            if (example && !envThere) {
                steps.add(
                        new Step(
                                "Put the key a real run needs in a .env file: copy .env.example to .env and fill in "
                                        + names,
                                ".env is for your machine and is ignored by git. A server uses the secret store instead (weave guide 9).",
                                "cp .env.example .env"));
            } else if (envThere) {
                steps.add(
                        new Step(
                                "Add " + names + " to .env (it is not set there or in your shell)",
                                ".env is for your machine and is ignored by git. weave reads it when you run or evaluate here.",
                                null));
            } else {
                steps.add(
                        new Step(
                                "Create a .env file beside the script with "
                                        + names
                                        + "=<your key>, and add .env to .gitignore",
                                "Keys never go in the script or the prompts. A server uses the secret store instead (weave guide 9).",
                                null));
            }
        }
        PreflightState preflight = PreflightState.read(dir, script);
        switch (preflight.state()) {
            case PreflightState.NONE, PreflightState.MOCK_ONLY ->
                    steps.add(
                            new Step(
                                    "Try one real answer before you spend more: run the preflight",
                                    "It sends one small probe per agent through the real prompts and checks the key, the model, a whole answer, that the shared prompt arrives and that grounded agents find sources. Capped at a few cents; it asks first. (--mock shows the checks for free.)",
                                    "weave preflight " + file));
            case PreflightState.STALE ->
                    steps.add(
                            new Step(
                                    "The script changed since the last preflight: run it again",
                                    "A result for an older script says nothing about this one.",
                                    "weave preflight " + file));
            case PreflightState.FAILING ->
                    steps.add(
                            new Step(
                                    "Fix what the preflight found: " + preflight.detail(),
                                    "A real answer is cut, empty or missing something the script depends on; everything built on it inherits that.",
                                    "weave preflight " + file));
            default -> {
                if (!RealRunRead.confirmed(dir, script))
                    steps.add(
                            new Step(
                                    "Read one real answer and say what is right and wrong with it",
                                    "A passing preflight shows the answer was whole, not that it was good. Read it before you build the dataset on it, then record that you did.",
                                    "weave next --confirm real-run"));
            }
        }
        steps.add(
                new Step(
                        "Look at what it would take to deploy it (writes nothing)",
                        "Env names, hosts and volumes are read from the script; the review's open findings are listed in the README it would write.",
                        "weave deploy " + file + " --dry-run"));
        steps.add(
                new Step(
                        hasDataset ? "Run it for real, capped" : "Run it once for real, capped",
                        "This is the first step that costs money. It states what it will do and asks before it spends.",
                        hasDataset
                                ? "weave eval " + file + " --max-tokens 200000"
                                : "weave run " + file + " --max-tokens 100000 -i <input>=\"...\""));
        if (hasRuns(dir)) {
            steps.add(
                    new Step(
                            "Turn a run that went wrong into a regression test",
                            "The runs you already have are the best test data there is; capture masks personal data and asks you to read the case.",
                            "weave capture --list"));
        }
        return steps;
    }

    private static boolean hasTests(Path dir) {
        for (String d : new String[] {"tests", "src/test/resources/tests"}) {
            Path t = dir.resolve(d);
            if (Files.isDirectory(t))
                try (Stream<Path> s = Files.walk(t, 2)) {
                    if (s.anyMatch(p -> p.getFileName().toString().endsWith(".test.yaml")))
                        return true;
                } catch (IOException e) {
                    return false;
                }
        }
        return false;
    }

    private static boolean hasRuns(Path dir) {
        for (String d : new String[] {".loom-triggers/runs", ".loom-triggers"}) {
            Path r = dir.resolve(d);
            if (Files.isDirectory(r))
                try (Stream<Path> s = Files.list(r)) {
                    if (s.anyMatch(p -> Files.isRegularFile(p.resolve("run.json")))) return true;
                } catch (IOException e) {
                    return false;
                }
        }
        return false;
    }

    /**
     * The script to look at: the file itself, or {@code main.loom} (or the only .loom file) in a
     * folder; null with the reason in {@code why}.
     */
    static Path find(Path given, StringBuilder why) {
        if (Files.isRegularFile(given)) return given;
        if (!Files.isDirectory(given)) {
            why.append(given).append(" is not a file or a folder.");
            return null;
        }
        Path main = io.github.llm4j.loom.init.ProjectLayout.script(given);
        if (main != null) return main;
        try (Stream<Path> s = Files.list(given)) {
            List<Path> loom = s.filter(p -> p.toString().endsWith(".loom")).sorted().toList();
            if (loom.size() == 1) return loom.get(0);
            why.append(
                    loom.isEmpty()
                            ? "there is no .loom file in "
                                    + given
                                    + ". To start one: weave init pipeline"
                            : "there is more than one .loom file here ("
                                    + loom.stream()
                                            .map(p -> p.getFileName().toString())
                                            .collect(Collectors.joining(", "))
                                    + "); say which: weave next <file>");
        } catch (IOException e) {
            why.append(e.getMessage());
        }
        return null;
    }

    private static String readme(Path dir) {
        try {
            Path r = dir.resolve("README.md");
            return Files.isRegularFile(r) ? Files.readString(r) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String first(List<String> items, int n) {
        if (items.isEmpty()) return "(no detail)";
        String joined =
                items.stream()
                        .limit(n)
                        .map(s -> s.replaceAll("\\s+", " ").strip())
                        .collect(Collectors.joining("; "));
        return items.size() > n ? joined + "; and " + (items.size() - n) + " more" : joined;
    }
}
