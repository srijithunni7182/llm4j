package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scripted test can assert that a run used its fallbacks, and which steps did. */
class ScriptedTestAssertsDegradedTest {
    @TempDir Path dir;

    private static final String SCRIPT =
            """
            agent Writer { model: "gemini-2.5-flash" system: "Write." }
            workflow Main(topic) {
                delegate "write {topic}" to Writer -> draft on_failure { set draft = "stub" }
                return draft
            }
            """;

    private static final String BASE =
            "script: ../main.loom\nworkflow: Main\ncases:\n  - name: c\n    input: { topic: tea }\n";

    private TestRunner.CaseResult only(String body) throws Exception {
        List<TestRunner.CaseResult> r = Wtest.run(dir, SCRIPT, BASE + body);
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    private static final String FAILS = "    fail: { Writer: { always: true } }\n";
    private static final String WORKS = "    models:\n      Writer: 'a fine draft'\n";

    @Test
    void aRunThatFellBackIsDegradedAndNamesTheStep() throws Exception {
        var r = only(FAILS + "    expect:\n      degraded: true\n      fallbacks: [ Writer ]\n");

        assertThat(r.failures()).isEmpty();
        assertThat(r.passed()).isTrue();
    }

    @Test
    void aCleanRunIsNotDegraded() throws Exception {
        var r = only(WORKS + "    expect:\n      degraded: false\n      fallbacks: []\n");

        assertThat(r.failures()).isEmpty();
    }

    @Test
    void theAssertionFailsWithAMessageWhenTheRunWasNotWhatTheTestSaid() throws Exception {
        var clean = only(WORKS + "    expect:\n      degraded: true\n");
        assertThat(clean.failures())
                .anyMatch(f -> f.startsWith("degraded: expected the run to be degraded"));

        var fell = only(FAILS + "    expect:\n      fallbacks: [ Other ]\n");
        assertThat(fell.failures()).anyMatch(f -> f.startsWith("fallbacks: expected [Other], got [Writer]"));
    }
}
