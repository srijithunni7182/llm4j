package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A11: every shipped sample has a suite of its own, and it passes. */
class ExamplesSuiteTest {

    private static final Path SAMPLES = Path.of("samples");

    private void passes(String sample) throws Exception {
        Path tests = SAMPLES.resolve(sample).resolve("tests");
        assertThat(tests).as(sample + " has a tests folder").isDirectory();
        TestRunner.Suite suite = TestRunner.load(null, List.of(tests));
        assertThat(suite.problems()).as(sample).isEmpty();
        List<TestRunner.CaseResult> results = TestRunner.run(suite, null, false, false);
        assertThat(results).as(sample).isNotEmpty();
        for (TestRunner.CaseResult r : results)
            assertThat(r.failures()).as(sample + ": " + r.name()).isEmpty();
    }

    @Test
    void digest() throws Exception {
        passes("digest");
    }

    @Test
    void contentFactory() throws Exception {
        passes("content_factory");
    }

    @Test
    void boardroom() throws Exception {
        passes("boardroom");
    }
}
