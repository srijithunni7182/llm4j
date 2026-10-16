package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The review's first gate, in code: does the project have unit tests at all? {@code outcome} is {@code has_tests} or {@code no_tests}. */
public final class CheckTestsExist implements Task {

    @Override public String getName() { return "CheckTestsExist"; }

    @Override public String getDescription() { return "Checks that the project contains unit tests"; }

    @Override public TaskEffect effect() { return TaskEffect.READS; }

    @Override public java.util.Set<String> okOutcomes() { return java.util.Set.of("has_tests"); }

    @Override
    public TaskResult run(TaskContext context) throws IOException {
        Path root = Sandbox.code(context.requireArg("project", String.class));
        List<Path> tests = ProjectFiles.list(root).stream()
                .filter(p -> ProjectFiles.isUnitTest(p) && ProjectFiles.isSource(p))
                .toList();
        long withAssertions = 0;
        for (Path test : tests) {
            String body = Files.readString(root.resolve(test));
            if (body.contains("@Test") || body.contains("assert") || body.contains("expect(") || body.contains("def test_") || body.contains("func Test") || body.contains("#[test]")) {
                withAssertions++;
            }
        }
        if (withAssertions == 0) return TaskResult.outcome("no_tests").reason("no unit test with an assertion was found in the project").with("count", 0);
        return TaskResult.outcome("has_tests").with("count", withAssertions);
    }
}
