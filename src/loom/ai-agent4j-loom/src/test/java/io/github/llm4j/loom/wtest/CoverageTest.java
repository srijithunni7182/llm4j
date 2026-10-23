package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.graph.GraphNode;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R32, R33, R19 of spec 11: which nodes of the graph the cases reached, by the ids {@code weave
 * graph} uses, and what a case would need to reach the rest.
 */
class CoverageTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            agent A { model: "m"  system: "s" }
            workflow Main(q) {
                delegate "one {q}" to A -> a retry 0 on_failure {
                    note "failed"
                    return "handled"
                }
                alt (a == "x") {
                    delegate "two" to A -> b
                    note "then"
                } else {
                    loop until (c == "done") max 3 {
                        delegate "three" to A -> c
                    } on_exhausted {
                        note "gave up"
                    }
                }
                return a
            }
            """;

    static String yaml(String... cases) {
        return "script: ../main.loom\nworkflow: Main\ncases:\n" + String.join("\n", cases) + "\n";
    }

    static final String THEN =
            "  - name: then\n    input: { q: hi }\n    models: { A: [ x, two ] }\n    expect:\n      branches: { n2: then }\n";
    static final String ELSE =
            "  - name: else\n    input: { q: hi }\n    models: { A: [ y, done ] }\n    expect:\n      branches: { n2: else }\n";
    static final String EXHAUSTED =
            "  - name: exhausted\n    input: { q: hi }\n    models: { A: [ y, no, no, no ] }\n";
    static final String FAILS =
            "  - name: fails\n    input: { q: hi }\n    models: { A: nothing }\n    fail: { A: { always: true, category: timeout } }\n    expect:\n      ran: [ on_failure of delegate A ]\n";

    Coverage coverage(String... cases) throws Exception {
        TestRunner.Suite suite = Wtest.load(dir, SCRIPT, yaml(cases));
        assertThat(suite.problems()).isEmpty();
        List<TestRunner.CaseResult> results = TestRunner.run(suite, null, false, false);
        for (TestRunner.CaseResult r : results) assertThat(r.failures()).as(r.name()).isEmpty();
        return TestRunner.coverage(suite, results);
    }

    @Test
    void stepIdsOfARunBecomeTheNodeIdsOfTheGraph() throws Exception {
        TestRunner.Suite suite = Wtest.load(dir, SCRIPT, yaml(THEN));
        StepResolver r = new StepResolver(suite.graph());
        assertThat(r.resolve("Main/s0")).contains("Main/n1");
        assertThat(r.resolve("Main/s1")).contains("Main/n2");
        assertThat(r.resolve("Main/s1/a0")).contains("Main/n3");
        assertThat(r.resolve("Main/s1/a1")).contains("Main/n4");
        assertThat(r.resolve("Main/s1/b0")).contains("Main/n5");
        assertThat(r.resolve("Main/s1/b0/r1.0")).contains("Main/n6");
        assertThat(r.resolve("Main/s1/b0/r2.0")).contains("Main/n6");
        assertThat(r.resolve("Main/s1/b0/x0")).contains("Main/n10");
        assertThat(r.resolve("Main/s0/f0")).contains("Main/n8");
        assertThat(r.resolve("Main/s0/f1")).contains("Main/n9");
        assertThat(r.resolve("Main/s2")).contains("Main/n7");
        assertThat(r.resolve("Main/s1/a0~2")).as("a generation after a rewind").contains("Main/n3");
        assertThat(r.resolve("Main/s9")).isEmpty();
        assertThat(r.resolve("Other/s0")).isEmpty();
        assertThat(r.resolve("")).isEmpty();
        assertThat(r.resolve("Main/s1/zz"))
                .as("what cannot be read stops at the last node")
                .contains("Main/n2");
    }

    @Test
    void theNodeIdsAreTheOnesTheGraphHas() throws Exception {
        TestRunner.Suite suite = Wtest.load(dir, SCRIPT, yaml(THEN));
        List<String> ids =
                suite.graph().workflows().get(0).nodes().stream()
                        .map(GraphNode::id)
                        .collect(Collectors.toList());
        TestRunner.CaseResult r = TestRunner.run(suite, null, false, false).get(0);
        StepResolver resolver = new StepResolver(suite.graph());
        for (String step : r.steps())
            assertThat(ids)
                    .as(step)
                    .contains(resolver.resolve(step).orElseThrow().substring("Main/".length()));
    }

    @Test
    void oneCaseCoversItsPathAndNamesWhatItMissed() throws Exception {
        Coverage c = coverage(THEN);
        assertThat(c.total()).isEqualTo(10);
        assertThat(c.reached()).contains("Main/n1", "Main/n2", "Main/n3", "Main/n4", "Main/n7");
        assertThat(c.covered()).isEqualTo(5);
        assertThat(c.percent()).isEqualTo(50);
        assertThat(c.missed())
                .extracting(Coverage.Missed::id)
                .containsExactly("n5", "n6", "n8", "n9", "n10");
    }

    @Test
    void casesAddUpAndEverythingCanBeReached() throws Exception {
        Coverage c = coverage(THEN, ELSE, EXHAUSTED, FAILS);
        assertThat(c.missed()).isEmpty();
        assertThat(c.covered()).isEqualTo(c.total());
        assertThat(c.percent()).isEqualTo(100);
        assertThat(c.branches()).allMatch(Coverage.Branch::covered);
    }

    @Test
    void theHintTellsWhatACaseWouldNeedToDo() throws Exception {
        Coverage c = coverage(THEN);
        var byId = c.missed().stream().collect(Collectors.toMap(Coverage.Missed::id, m -> m));
        assertThat(byId.get("n5").how()).contains("make the condition of \"a==x?\" false");
        assertThat(byId.get("n6").how()).contains("must turn at least once");
        assertThat(byId.get("n10").how()).contains("reach its maximum without finishing");
        assertThat(byId.get("n8").how())
                .contains("fail: { A: { always: true, category: server_error } }");
        assertThat(byId.get("n5").line()).isEqualTo(11);
    }

    @Test
    void blocksAreCoveredWhenOneOfTheirStepsRan() throws Exception {
        Coverage c = coverage(THEN);
        assertThat(c.branches())
                .extracting(b -> b.owner() + " " + b.branch() + " " + b.covered())
                .contains(
                        "n2 then true",
                        "n2 else false",
                        "n5 body false",
                        "n5 exhausted false",
                        "n1 failure false");
    }

    @Test
    void theBranchesAssertionSaysWhichArmRanAndThatTheOtherDidNot() throws Exception {
        TestRunner.Suite suite =
                Wtest.load(
                        dir,
                        SCRIPT,
                        yaml(
                                "  - name: wrong arm\n    input: { q: hi }\n    models: { A: [ x, two ] }\n    expect:\n      branches: { n2: else }\n",
                                "  - name: right arm\n    input: { q: hi }\n    models: { A: [ x, two ] }\n    expect:\n      branches: { 'a==x?': then }\n"));
        List<TestRunner.CaseResult> r = TestRunner.run(suite, null, false, false);
        assertThat(r.get(0).failures())
                .singleElement()
                .asString()
                .contains("branches.n2: expected the else arm to run, it did not");
        assertThat(r.get(1).failures()).isEmpty();
    }

    @Test
    void theReportPrintsTheUncoveredNodesWithTheirHints() throws Exception {
        Coverage c = coverage(THEN);
        String text = Reports.uncovered(c);
        assertThat(text)
                .contains("Nodes covered 5/10 (50 %)")
                .contains("Blocks never run:")
                .contains("Main n5 ")
                .contains("# to reach this: make the condition of \"a==x?\" false");
    }
}
