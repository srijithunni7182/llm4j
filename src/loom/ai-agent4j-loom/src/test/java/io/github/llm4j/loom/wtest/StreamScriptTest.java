package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R9 of spec 11: a streaming agent is scripted by the pieces of its reply, and a steer or a stop
 * can come between two pieces.
 */
class StreamScriptTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            agent Writer { model: "m"  system: "Write."  tools: [web_search]  steerable: true  streaming: true }
            agent Plain { model: "m"  system: "Write." }
            workflow Main(q) {
                delegate "write {q}" to Writer -> r on_stopped { use partial }
                return r
            }
            workflow Cut(q) {
                delegate "write {q}" to Writer -> r on_stopped { skip }
                return "ended"
            }
            workflow Other(q) {
                delegate "write {q}" to Plain -> r
                return r
            }
            """;

    TestRunner.CaseResult run(String workflow, String body) throws Exception {
        String yaml =
                "script: ../main.loom\nworkflow: "
                        + workflow
                        + "\ncases:\n  - name: c\n    input: { q: hi }\n"
                        + body;
        List<TestRunner.CaseResult> r = Wtest.run(dir, SCRIPT, yaml);
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    @Test
    void thePiecesJoinIntoTheAnswer() throws Exception {
        TestRunner.CaseResult r =
                run(
                        "Main",
                        "    models:\n      Writer: { stream: ['Hel', 'lo ', 'world'] }\n    expect:\n      result: Hello world\n      calls: { Writer: 1 }\n");
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void quotesAndNewlinesInAPieceSurvive() throws Exception {
        TestRunner.CaseResult r =
                run(
                        "Main",
                        "    models:\n      Writer: { stream: [\"He said \\\"\", \"hi\\\"\\nthen left\"] }\n    expect:\n      result: \"He said \\\"hi\\\"\\nthen left\"\n");
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void anAgentThatDoesNotStreamStillGetsTheJoinedAnswer() throws Exception {
        TestRunner.CaseResult r =
                run(
                        "Other",
                        "    models:\n      Plain: { stream: ['a', 'b'] }\n    expect:\n      result: ab\n");
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aStopBetweenPiecesEndsTheReplyThereAndItsHalfIsNotUsed() throws Exception {
        String model = "    models:\n      Writer: { stream: ['first ', 'second ', 'third'] }\n";
        TestRunner.CaseResult cut =
                run(
                        "Cut",
                        model
                                + "    stop: { after_chunk: { Writer: 2 }, mode: cancel }\n    expect:\n      final: completed\n      result: ended\n      calls: { Writer: 1 }\n      context: { r: absent }\n");
        assertThat(cut.failures()).isEmpty();
        TestRunner.CaseResult whole =
                run("Cut", model + "    expect:\n      context: { r: first second third }\n");
        assertThat(whole.failures()).as("without a stop the whole reply arrives").isEmpty();
    }

    @Test
    void aSteerBetweenPiecesMakesTheAgentAnswerAgain() throws Exception {
        TestRunner.CaseResult r =
                run(
                        "Main",
                        "    models:\n      Writer:\n        - { stream: ['one ', 'two ', 'three'] }\n        - 'second try'\n    steer:\n      - { after_chunk: { Writer: 2 }, text: 'shorter please' }\n    expect:\n      calls: { Writer: 2 }\n      result: second try\n");
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void theCheckerKnowsAfterChunk() throws Exception {
        TestRunner.Suite ok =
                Wtest.load(
                        dir,
                        SCRIPT,
                        "script: ../main.loom\nworkflow: Main\ncases:\n  - name: c\n    input: { q: hi }\n    models: { Writer: { stream: [a] } }\n    stop: { after_chunk: { Writer: 1 } }\n");
        assertThat(ok.problems()).isEmpty();
        TestRunner.Suite bad =
                Wtest.load(
                        dir,
                        SCRIPT,
                        "script: ../main.loom\nworkflow: Main\ncases:\n  - name: c\n    input: { q: hi }\n    models: { Writer: { stream: [a] } }\n    stop: { after_chunk: { Writter: 1 } }\n");
        assertThat(bad.problems()).anyMatch(p -> p.contains("Writter") && p.contains("Writer"));
    }
}
