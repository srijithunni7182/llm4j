package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R29 of spec 11: a case with no real task or file input and output takes a few milliseconds, and a
 * suite of a hundred runs in seconds.
 */
class TestSpeedTest {

    @TempDir Path dir;

    @Test
    void aHundredCasesRunFastAndTheMedianCaseTakesUnder20Milliseconds() throws Exception {
        TestRunner.Suite s = Wtest.load(dir, Wtest.TRIAGE, new RunSelectionTest().yamlOf(100));
        assertThat(s.problems()).isEmpty();
        TestRunner.run(s, "case 01", false, false); // warm the classes
        long start = System.nanoTime();
        List<TestRunner.CaseResult> serial = TestRunner.run(s, TestRunner.Options.defaults());
        long serialMs = (System.nanoTime() - start) / 1_000_000;
        List<Long> times = serial.stream().map(TestRunner.CaseResult::millis).sorted().toList();
        long median = times.get(times.size() / 2);
        System.out.println(
                "100 cases serially: " + serialMs + " ms, median case " + median + " ms");
        assertThat(serial).hasSize(100).allMatch(TestRunner.CaseResult::passed);
        assertThat(median).as("median case, ms").isLessThan(20);
        assertThat(serialMs).as("100 cases serially, ms").isLessThan(5000);
        start = System.nanoTime();
        TestRunner.run(
                s,
                new TestRunner.Options(null, false, false, List.of(), List.of(), 4, false, null));
        System.out.println(
                "100 cases on 4 workers: " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
}
