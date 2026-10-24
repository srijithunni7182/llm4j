package io.github.llm4j.loom.review.testing;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.review.cost.RunProfile;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The testability lens: is the workflow testable, and is it tested? It reads the test files beside
 * the script ({@code tests/*.test.yaml}) and the golden datasets ({@code eval/golden}); it never
 * runs a test.
 *
 * <p>LT03 (a node the last coverage report lists as unreached) is not built: it needs the coverage
 * report of the workflow-test phase, which does not exist yet.
 */
public final class TestabilityRules implements FindingSource {

    @Override
    public String name() {
        return "testability rules";
    }

    /** What is known about these rules, for {@code --explain}. */
    public static List<RuleInfo> catalog() {
        return List.of(
                info(
                        "LT01",
                        null,
                        "The script has no test file",
                        "Tests for a workflow are files named tests/*.test.yaml beside the script. With none, a change to a prompt, an agent or a route is checked only by running it for real. Medium when the script can write, send or ask a person, else low. What to do: write a first test that scripts the models and checks the route."),
                info(
                        "LT02",
                        Severity.LOW,
                        "An agent or classifier is scripted by no test",
                        "When tests exist, each agent and classifier should be scripted (models:) by at least one, or its behaviour in the workflow is never exercised. What to do: add a case that names it."),
                info(
                        "LT04",
                        Severity.MEDIUM,
                        "Retries and failure paths are never tested",
                        "A script with retry, on_failure, rewind or on_stopped has paths that run only when something fails. When tests exist and none injects a failure (fail:), those paths are untested. What to do: add a case with a fail: entry."),
                info(
                        "LT05",
                        Severity.LOW,
                        "A person's answer is never scripted",
                        "A script that asks a person (human_prompt, decide, an approval) needs a test that supplies answers (humans:), or the branches after the question are untested. What to do: add a case with humans:."),
                info(
                        "LT06",
                        Severity.LOW,
                        "An agent has no golden dataset (heuristic)",
                        "weave eval compares an agent's answers with a golden dataset in eval/golden/<agent>.yaml. This rule goes by that file name, so it runs only with heuristics on or when named. What to do: weave eval --init."),
                info(
                        "LT07",
                        Severity.MEDIUM,
                        "A test names something the script no longer has",
                        "A test that scripts or checks an agent, classifier, tool or task that is not in the script is stale: it passes or fails for the wrong reason. What to do: rename it in the test, or delete the case."));
    }

    private static RuleInfo info(String id, Severity s, String title, String explain) {
        return new RuleInfo(id, Lens.TESTABILITY, s, title, explain, "testability");
    }

    @Override
    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
        if (!settings.lensOn(Lens.TESTABILITY)) return;
        Path script = input.baseDir().resolve(Path.of(input.file()).getFileName());
        TestSuite suite = TestSuite.beside(script);
        for (String bad : suite.unreadable())
            sink.note(new Note(Note.Kind.INFO, "LT01", "test file " + bad + " could not be read"));
        String file = input.file();
        boolean acts = false;
        boolean asks = false;
        for (AgentDef a : input.script().getAgents()) {
            acts |= !RunProfile.effectKinds(a, input).isEmpty();
            asks |= a.isApproveAll() || !a.getApprove().isEmpty();
        }
        Facts facts = facts(input);
        asks |= facts.asksPeople;

        if (suite.isEmpty()) {
            sink.finding(
                    finding(
                            "LT01",
                            acts || asks ? Severity.MEDIUM : Severity.LOW,
                            Location.at(file, 0),
                            "This script has no test file",
                            "Without tests, a change to a prompt, an agent or a route is checked only by running the workflow for real"
                                    + (acts ? ", and this one can write or send" : "")
                                    + (asks ? ", and it asks a person" : "")
                                    + ".",
                            "Write tests/"
                                    + stem(file)
                                    + ".test.yaml with a case that scripts the models and checks the route."));
        } else {
            for (AgentDef a : input.script().getAgents())
                if (!suite.scripts(a.getName()))
                    sink.finding(
                            finding(
                                    "LT02",
                                    Severity.LOW,
                                    Location.at(file, a.getLine()),
                                    "Agent " + a.getName() + " is scripted by no test",
                                    "Its answers are never faked in a test, so no test exercises how the workflow behaves with what it says.",
                                    "Add a case with models: { " + a.getName() + ": \"…\" }."));
            for (IntentClassifierDef c : input.script().getIntentClassifiers())
                if (!suite.scripts(c.getName()))
                    sink.finding(
                            finding(
                                    "LT02",
                                    Severity.LOW,
                                    Location.at(file, c.getLine()),
                                    "Classifier " + c.getName() + " is scripted by no test",
                                    "Its answers are never faked in a test, so no test exercises the routes it chooses.",
                                    "Add a case with models: { " + c.getName() + ": { … } }."));
            if (facts.failurePath > 0 && !suite.injectsFailures())
                sink.finding(
                        finding(
                                "LT04",
                                Severity.MEDIUM,
                                Location.at(file, facts.failurePath),
                                "The retries and failure paths of this script are never tested",
                                "No test injects a failure (fail:), so retry, on_failure, rewind and on_stopped run only in production.",
                                "Add a case with fail: { Agent: { times: 1, category: timeout } }."));
            if (asks && !suite.suppliesHumans())
                sink.finding(
                        finding(
                                "LT05",
                                Severity.LOW,
                                Location.at(file, facts.firstQuestion),
                                "No test supplies a person's answer",
                                "The script asks a person, and no test has humans:, so what happens after the answer is untested.",
                                "Add a case with humans: [ \"yes\" ]."));
            staleNames(input, suite, sink);
        }
        goldenDatasets(input, settings, sink);
    }

    private static void staleNames(ReviewInput input, TestSuite suite, Sink sink) {
        Set<String> agents = new LinkedHashSet<>();
        input.script().getAgents().forEach(a -> agents.add(a.getName()));
        input.script().getIntentClassifiers().forEach(c -> agents.add(c.getName()));
        Set<String> tools = new LinkedHashSet<>();
        input.script().getTools().forEach(t -> tools.add(t.getName()));
        input.script().getAgents().forEach(a -> tools.addAll(a.getTools()));
        Set<String> tasks = new LinkedHashSet<>();
        for (WorkflowDef w : input.script().getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    st -> {
                        if (st instanceof RunStmt r) tasks.add(r.getTaskName());
                    });
        for (TestSuite.TestFile f : suite.files()) {
            report(f, "agent or classifier", f.agentNames(), agents, sink);
            report(f, "tool", f.toolNames(), tools, sink);
            report(f, "task", f.taskNames(), tasks, sink);
        }
    }

    private static void report(
            TestSuite.TestFile f, String what, Set<String> used, Set<String> known, Sink sink) {
        for (String name : used)
            if (!known.contains(name))
                sink.finding(
                        finding(
                                "LT07",
                                Severity.MEDIUM,
                                Location.at(f.path(), 0),
                                "A test names the "
                                        + what
                                        + " "
                                        + name
                                        + ", which the script does not have",
                                "A test that scripts or checks something that no longer exists is stale: it passes or fails for the wrong reason.",
                                "Rename it in the test"
                                        + Suggest.hint(name, known)
                                        + ", or delete the case."));
    }

    private static void goldenDatasets(ReviewInput input, ReviewSettings settings, Sink sink) {
        Boolean enabled = settings.rule("LT06").enabled();
        if (!settings.heuristics() && !Boolean.TRUE.equals(enabled)) return;
        Path golden = input.baseDir().resolve("eval").resolve("golden");
        for (AgentDef a : input.script().getAgents()) {
            Optional<Path> found =
                    List.of(
                                    golden.resolve(a.getName() + ".yaml"),
                                    golden.resolve(a.getName() + ".yml"))
                            .stream()
                            .filter(Files::isRegularFile)
                            .findFirst();
            if (found.isEmpty())
                sink.finding(
                        finding(
                                "LT06",
                                Severity.LOW,
                                Location.at(input.file(), a.getLine()),
                                "Agent " + a.getName() + " has no golden dataset",
                                "Without examples of good answers, a prompt change to this agent cannot be compared with before.",
                                "weave eval --init creates eval/golden/" + a.getName() + ".yaml."));
        }
    }

    private record Facts(int failurePath, boolean asksPeople, int firstQuestion) {}

    private static Facts facts(ReviewInput input) {
        int[] failure = {0};
        int[] question = {0};
        for (WorkflowDef w : input.script().getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (failure[0] == 0 && hasFailurePath(s)) failure[0] = s.getLine();
                        if (question[0] == 0
                                && (s instanceof HumanPromptStmt || s instanceof DecideStmt))
                            question[0] = s.getLine();
                    });
        return new Facts(failure[0], question[0] > 0, question[0]);
    }

    private static boolean hasFailurePath(Statement s) {
        if (s instanceof DelegateStmt d)
            return d.getRetryCount() > 0
                    || !d.getOnFailure().isEmpty()
                    || !d.getOnStopped().isEmpty();
        if (s instanceof RunStmt r) return r.getRetryCount() > 0 || !r.getOnFailure().isEmpty();
        if (s instanceof IntentClassifyStmt k)
            return k.getRetryCount() > 0 || !k.getOnFailure().isEmpty();
        return s instanceof RewindStmt;
    }

    private static String stem(String file) {
        String n = Path.of(file).getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    private static Finding finding(
            String rule, Severity sev, Location where, String title, String why, String fix) {
        return new Finding(
                rule,
                Lens.TESTABILITY,
                sev,
                where,
                title,
                why,
                fix,
                List.of(),
                Optional.empty(),
                new ArrayList<>(),
                "testability");
    }
}
