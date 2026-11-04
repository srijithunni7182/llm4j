package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 24, R5: an extractor is scripted in a {@code weave test} case like an agent. */
class ExtractScriptedTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            extractor Invoice {
                model: "gemini-2.5-flash"
                fields { vendor: string, total: number }
                optional: [total]
            }
            workflow Main(text) {
                extract Invoice from text -> invoice on_failure { return "unreadable" }
                alt (invoice.total == missing) { return "no total" } else { return invoice.vendor }
            }
            """;

    private TestRunner.CaseResult one(String yaml) throws Exception {
        var results = Wtest.run(dir, SCRIPT, "script: ../main.loom\nworkflow: Main\ncases:\n" + yaml);
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @Test
    void anExtractorIsAnsweredFromTheCase() throws Exception {
        var r =
                one(
                        "  - name: reads\n    input: { text: hi }\n    models: { Invoice: { vendor: Acme, total: 3 } }\n    expect:\n      result: Acme\n      calls: { Invoice: 1 }\n");

        assertThat(r.failures()).isEmpty();
    }

    @Test
    void anOptionalFieldLeftOutReadsAsMissing() throws Exception {
        var r =
                one(
                        "  - name: none\n    input: { text: hi }\n    models: { Invoice: { vendor: Acme } }\n    expect:\n      result: no total\n");

        assertThat(r.failures()).isEmpty();
    }

    @Test
    void anExtractorCanFailToo() throws Exception {
        var r =
                one(
                        "  - name: down\n    input: { text: hi }\n    models: { Invoice: { vendor: Acme } }\n    fail: { Invoice: { always: true, category: rate_limit } }\n    expect:\n      result: unreadable\n");

        assertThat(r.failures()).isEmpty();
    }

    @Test
    void anExtractorWithNoScriptedAnswerFailsTheCaseNamingIt() throws Exception {
        var r = one("  - name: silent\n    input: { text: hi }\n    expect:\n      result: unreadable\n");

        assertThat(String.join("\n", r.failures())).contains("Invoice");
    }

    @Test
    void aMisspeltExtractorNameInModelsIsRefusedWithAHint() throws Exception {
        var suite =
                Wtest.load(
                        dir,
                        SCRIPT,
                        "script: ../main.loom\nworkflow: Main\ncases:\n  - name: typo\n    input: { text: hi }\n    models: { Invoce: { vendor: A } }\n    expect:\n      result: A\n");

        assertThat(String.join("\n", suite.problems())).contains("Invoce").contains("Invoice");
    }
}
