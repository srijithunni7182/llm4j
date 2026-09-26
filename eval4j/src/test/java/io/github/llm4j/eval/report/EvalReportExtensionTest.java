package io.github.llm4j.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

/**
 * Exercises {@link EvalReportExtension} as a real JUnit 5 extension via the platform test kit,
 * since the extension's behavior only makes sense in terms of engine callbacks
 * (testSuccessful/testFailed/afterAll), not as a plain unit under direct method calls.
 */
class EvalReportExtensionTest {

    @ExtendWith(EvalReportExtension.class)
    static class SampleSuite {

        @Test
        void passingCase() {}

        @Test
        void failingCase() {
            throw new AssertionError("expected failure for report");
        }

        @Test
        @Disabled("disabled tests should not appear in the report")
        void disabledCase() {}
    }

    @Test
    void printsPassFailSummaryAfterAllTestsRun() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        Events testEvents;
        try {
            System.setOut(new PrintStream(captured));
            testEvents =
                    EngineTestKit.engine("junit-jupiter")
                            .selectors(selectClass(SampleSuite.class))
                            .execute()
                            .testEvents();
        } finally {
            System.setOut(originalOut);
        }

        testEvents.assertStatistics(stats -> stats.succeeded(1).failed(1));

        String output = captured.toString();
        assertThat(output).contains("[PASS]").contains("passingCase");
        assertThat(output).contains("[FAIL]").contains("failingCase").contains("expected failure for report");
        assertThat(output).contains("1/2 passed");
        assertThat(output).doesNotContain("disabledCase");
    }
}
