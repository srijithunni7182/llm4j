package io.github.llm4j.loom.wtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Test helper: a script and a test file in a folder, loaded and run. */
final class Wtest {

    private Wtest() {}

    /** The triage script used by most cases. */
    static final String TRIAGE =
            """
            agent Replier { model: "gemini-2.5-flash"  system: "Answer the customer politely." }
            agent Escalator { model: "gemini-2.5-flash"  system: "Summarise the case." }
            intentclassifier Intent {
                model: "gemini-2.5-flash"
                choose kind: order_status | refund | other
                ask urgent: "Is the customer threatening to leave?"
            }
            workflow Main(message) {
                intentclassify Intent on message -> intent
                alt (intent.kind == "order_status") {
                    delegate "Reply to: {message}" to Replier -> reply
                    note "replied"
                    return reply
                } else {
                    delegate "Summarise: {message}" to Escalator -> summary
                    return summary
                }
            }
            """;

    static Path write(Path dir, String script, String yaml) throws IOException {
        Files.writeString(dir.resolve("main.loom"), script);
        Path tests = Files.createDirectories(dir.resolve("tests"));
        Path file = tests.resolve("main.test.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    static TestRunner.Suite load(Path dir, String script, String yaml) throws Exception {
        write(dir, script, yaml);
        return TestRunner.load(dir.resolve("main.loom"), List.of(dir.resolve("tests")));
    }

    static List<TestRunner.CaseResult> run(Path dir, String script, String yaml) throws Exception {
        TestRunner.Suite suite = load(dir, script, yaml);
        if (!suite.problems().isEmpty())
            throw new AssertionError("the test file has problems: " + suite.problems());
        return TestRunner.run(suite, null, false, false);
    }

    /** The case YAML with the common triage preamble and the given body, indented as a case. */
    static String triageCases(String... cases) {
        return "script: ../main.loom\nworkflow: Main\ncases:\n" + String.join("\n", cases) + "\n";
    }
}
