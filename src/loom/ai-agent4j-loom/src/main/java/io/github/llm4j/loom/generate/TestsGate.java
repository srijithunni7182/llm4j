package io.github.llm4j.loom.generate;

import io.github.llm4j.loom.wtest.Coverage;
import io.github.llm4j.loom.wtest.TestRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * G3 and G4: the project's own tests (phase 11, scripted models and tools, nothing spent and no
 * call to the agents it generated) fit the script and pass, and between them they reach at least
 * the required share of the script's steps. A failure carries the failing assertion and, for
 * coverage, each step no case reaches with a hint for a case that would.
 */
public final class TestsGate {

    private TestsGate() {}

    /** G3: there are tests, they fit the script, and every case passes. */
    public static Gate passing() {
        return passing(false);
    }

    /**
     * G3 for a project being generated: the tool writes the tests that reach every branch itself
     * ({@link TestSynth}) and takes the model's cases that do not hold out of the way ({@link
     * DerivedTests}).
     */
    public static Gate passing(boolean derive) {
        return new Gate() {
            @Override
            public String id() {
                return "G3";
            }

            @Override
            public GateResult run(Workspace ws, Plan plan) {
                return result(ws, "G3", "tests", 0, derive);
            }
        };
    }

    /**
     * G4: the cases that pass between them reach at least {@code percent} of the script's steps.
     */
    public static Gate coverage(int percent) {
        return coverage(percent, false);
    }

    /** G4, after the tool-made tests are in the project (see {@link #passing(boolean)}). */
    public static Gate coverage(int percent, boolean derive) {
        return new Gate() {
            @Override
            public String id() {
                return "G4";
            }

            @Override
            public GateResult run(Workspace ws, Plan plan) {
                return result(ws, "G4", "coverage", percent, derive);
            }
        };
    }

    private static GateResult result(
            Workspace ws, String id, String name, int percent, boolean derive) {
        List<GateResult.Problem> out = new ArrayList<>();
        try {
            // G3 puts the tool-made file in; G4 runs after it on the same project
            if (derive && (id.equals("G3") || !DerivedTests.present(ws))) DerivedTests.refresh(ws);
            List<Path> files = ws.testFiles();
            if (files.isEmpty()) {
                out.add(
                        new GateResult.Problem(
                                "",
                                0,
                                "",
                                "The project has no tests/*.test.yaml. Write cases for every route and every limit of the workflow."));
                return new GateResult(id, name, out);
            }
            TestRunner.Suite suite = TestRunner.load(ws.script(), files);
            if (!suite.problems().isEmpty()) {
                if (id.equals("G3")) {
                    for (String p : suite.problems())
                        out.add(new GateResult.Problem("tests", 0, "", p));
                    if (suite.problems().stream().anyMatch(p -> p.contains("is not a step")))
                        out.add(
                                new GateResult.Problem(
                                        "tests",
                                        0,
                                        "",
                                        "The steps a case can name in route: and never: are "
                                                + stepLabels(suite)
                                                + "."));
                }
                return new GateResult(id, name, out);
            }
            List<TestRunner.CaseResult> results = TestRunner.run(suite, null, false, false);
            if (results.isEmpty()) {
                out.add(new GateResult.Problem("tests", 0, "", "The test files hold no cases."));
                return new GateResult(id, name, out);
            }
            if (id.equals("G3")) {
                for (TestRunner.CaseResult r : results)
                    if (!r.passed())
                        out.add(
                                new GateResult.Problem(
                                        fileName(r.file()),
                                        0,
                                        "",
                                        "case \""
                                                + r.name()
                                                + "\" failed: "
                                                + String.join("; ", r.failures())));
            } else {
                // coverage counts only when the cases pass; failing ones are G3's to report
                Coverage c =
                        TestRunner.coverage(
                                suite,
                                results.stream().filter(TestRunner.CaseResult::passed).toList());
                if (c.percent() < percent) {
                    out.add(
                            new GateResult.Problem(
                                    "",
                                    0,
                                    "",
                                    "The cases reach "
                                            + c.percent()
                                            + "% of the steps; at least "
                                            + percent
                                            + "% is needed. Add cases for what is not reached:"));
                    for (Coverage.Missed m : c.missed())
                        out.add(
                                new GateResult.Problem(
                                        "main.loom",
                                        m.line(),
                                        "",
                                        "not reached: " + m.label() + " — " + m.how()));
                }
            }
        } catch (Exception e) {
            out.add(
                    new GateResult.Problem(
                            "tests",
                            0,
                            "",
                            "The tests could not be run: "
                                    + String.valueOf(e.getMessage())
                                            .replace(ws.dir().toString() + "/", "")
                                            .replace(
                                                    ws.dir().toAbsolutePath().toString() + "/",
                                                    "")));
        }
        return new GateResult(id, name, out);
    }

    /** The labels of the script's steps, as route: and never: write them. */
    private static String stepLabels(TestRunner.Suite suite) {
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        for (var w : suite.graph().workflows())
            for (var n : w.nodes())
                if (!n.kind().equals("start") && !n.kind().equals("end")) labels.add(n.label());
        return String.join(", ", labels);
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
