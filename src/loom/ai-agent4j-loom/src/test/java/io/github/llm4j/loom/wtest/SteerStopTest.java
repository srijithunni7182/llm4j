package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R17 of spec 11: a case can steer a running agent and stop it, through the executor's own
 * controls.
 */
class SteerStopTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            agent Researcher { model: "m"  system: "Research."  tools: [web_search]  steerable: true  streaming: true }
            workflow Main(q) {
                delegate "research {q}" to Researcher -> r on_stopped { use partial }
                return r
            }
            """;

    static final String MODEL =
            "    models:\n      Researcher:\n        when:\n          - { contains: 'focus on pricing', reply: 'pricing report' }\n          - { otherwise: { call: web_search, with: { query: x } } }\n    tools: { web_search: 'some results' }\n";

    static String cases(String body) {
        return "script: ../main.loom\nworkflow: Main\ncases:\n  - name: c\n    input: { q: market }\n"
                + MODEL
                + body;
    }

    TestRunner.CaseResult run(String body) throws Exception {
        List<TestRunner.CaseResult> r = Wtest.run(dir, SCRIPT, cases(body));
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    @Test
    void whatThePersonSaysReachesTheAgentOnItsNextRound() throws Exception {
        TestRunner.CaseResult r =
                run(
                        "    steer:\n      - { after_call: { Researcher: 1 }, text: 'focus on pricing' }\n    expect:\n      result: pricing report\n      calls: { Researcher: 2, web_search: 1 }\n");
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void withoutASteerTheAgentKeepsLookingAndTheCaseSaysWhatHappened() throws Exception {
        TestRunner.CaseResult r = run("    expect:\n      result: pricing report\n");
        assertThat(r.passed()).isFalse();
    }

    @Test
    void aWrapUpMakesTheAgentFinishWithWhatItHas() throws Exception {
        TestRunner.CaseResult r =
                run(
                        "    stop: { after_call: { Researcher: 1 }, mode: wrap_up }\n    expect:\n      final: completed\n");
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aCancelRunsTheOnStoppedBlockAndTheCaseSeesIt() throws Exception {
        TestRunner.CaseResult r =
                run(
                        "    stop: { after_call: { Researcher: 1 }, mode: cancel }\n    expect:\n      final: completed\n      calls: { Researcher: 1 }\n");
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void steerAndStopAreCheckedAgainstTheScript() throws Exception {
        var suite =
                Wtest.load(
                        dir,
                        SCRIPT,
                        cases(
                                "    steer:\n      - { after_call: { Reseacher: 1 }, text: hi }\n    stop: { after_call: { Researcher: 1 }, mode: halt }\n"));
        assertThat(suite.problems())
                .anyMatch(
                        p ->
                                p.contains("steer")
                                        && p.contains("'Reseacher'")
                                        && p.contains("did you mean 'Researcher'"))
                .anyMatch(p -> p.contains("stop.mode"));
    }
}
