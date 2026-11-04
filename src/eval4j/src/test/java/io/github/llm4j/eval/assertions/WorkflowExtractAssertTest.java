package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.export.WorkflowTrace.Event;
import io.github.llm4j.eval.report.EvalRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trajectory assertions for extract steps (phase 24): how often an extractor ran and which fields it found. The trace carries field names, never values. */
class WorkflowExtractAssertTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-EXTRACT-0001");
        EvalRecorder.reset();
        EvalRecorder.activate();
        EvalRun.get().bindTest("com.acme.TaskTest", "refund");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
        EvalRecorder.reset();
    }

    private static Event ev(double t, String type, String agent, Map<String, Object> data) {
        return new Event(t, type, agent, "Main/s0", null, "", data);
    }

    private static Event done(double t, String extractor, List<String> present, List<String> missing) {
        return ev(
                t,
                "extract_end",
                null,
                Map.of("extractor", extractor, "present", present, "missing", missing));
    }

    private static WorkflowTrace trace(Event... events) {
        return new WorkflowTrace(
                "wf",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(events),
                List.of(),
                null,
                0,
                null);
    }

    private final WorkflowTrace read =
            trace(
                    done(1, "Invoice", List.of("vendor", "currency"), List.of("total")),
                    done(2, "Address", List.of("street"), List.of()));

    @Test
    void theTraceKnowsWhichExtractorsRanAndWhatTheyFound() {
        assertThat(read.extractorsInOrder()).containsExactly("Invoice", "Address");
        assertThat(read.extractRuns("Invoice")).isEqualTo(1);
        assertThat(read.extractRuns("Nope")).isZero();
        assertThat(read.fieldsFound("Invoice")).containsExactly("vendor", "currency");
        assertThat(read.fieldsMissing("Invoice")).containsExactly("total");
    }

    @Test
    void aReplayedExtractDidNotRun() {
        WorkflowTrace replayed =
                trace(ev(0, "extract_replayed", null, Map.of("extractor", "Invoice")));
        assertThat(replayed.extractRuns("Invoice")).isZero();
    }

    @Test
    void passingAssertionsAreRecorded() throws Exception {
        WorkflowAssertions.assertThat(read)
                .runsExtractTimes("Invoice", 1)
                .runsExtractTimes("Nope", 0)
                .extractedField("Invoice", "vendor")
                .leftMissing("Invoice", "total");
        EvalRun.get().finish();
        String evals = Files.readString(root.resolve("runs/RUN-EXTRACT-0001/evaluations.jsonl"));
        assertThat(evals).contains("extractors-run");
    }

    @Test
    void failuresNameWhatHappened() {
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(read).runsExtractTimes("Invoice", 2))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Invoice")
                .hasMessageContaining("1 time(s)");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(read).extractedField("Invoice", "total"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("total")
                .hasMessageContaining("[vendor, currency]");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(read).leftMissing("Invoice", "vendor"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("vendor");
    }
}
