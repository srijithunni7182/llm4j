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
        return new Gate() {
            @Override
            public String id() {
                return "G3";
            }

            @Override
            public GateResult run(Workspace ws, Plan plan) {
                return result(ws, "G3", "tests", 0);
            }
        };
    }

    /**
     * G4: the cases that pass between them reach at least {@code percent} of the script's steps.
     */
    public static Gate coverage(int percent) {
        return new Gate() {
            @Override
            public String id() {
                return "G4";
            }

            @Override
            public GateResult run(Workspace ws, Plan plan) {
                return result(ws, "G4", "coverage", percent);
            }
        };
    }

    private static GateResult result(Workspace ws, String id, String name, int percent) {
        List<GateResult.Problem> out = new ArrayList<>();
        try {
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
                if (id.equals("G3"))
                    for (String p : suite.problems())
                        out.add(new GateResult.Problem("tests", 0, "", p));
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
                            "tests", 0, "", "The tests could not be run: " + e.getMessage()));
        }
        return new GateResult(id, name, out);
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
