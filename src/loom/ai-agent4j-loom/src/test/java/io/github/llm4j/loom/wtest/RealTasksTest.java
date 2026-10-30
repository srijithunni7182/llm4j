package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 22 R7: a case may name {@code real: [Task]}; the harness then runs the actual class of that
 * task instead of a scripted result. Only a task that declares no effect on the world may be real;
 * one that changes things, or does not say, is refused with the reason.
 */
class RealTasksTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            workflow Main(who) {
                run ServiceTask(who = who) -> greeting
                run IssueRefund(order = "o1", amount = 5.0) -> refund
                return greeting
            }
            """;

    private static String cases(String... body) {
        return Wtest.triageCases(body);
    }

    private TestRunner.Suite load(String yaml) throws Exception {
        return Wtest.load(dir, SCRIPT, yaml);
    }

    @Test
    void aRealTaskRunsItsActualCodeWhileTheOtherStaysScripted() throws Exception {
        TestRunner.Suite suite =
                load(
                        cases(
                                "  - name: greets\n    input: { who: Ada }\n    real: [ServiceTask]\n    tasks: { IssueRefund: ok }\n    expect:\n      result: { value: 'from the service loader: Ada' }\n      calls: { ServiceTask: 1, IssueRefund: 1 }\n"));

        assertThat(suite.problems()).isEmpty();
        assertThat(TestRunner.run(suite, null, false, false))
                .allSatisfy(r -> assertThat(r.failures()).as(r.name()).isEmpty());
    }

    @Test
    void aTaskThatChangesThingsCannotBeRealAndTheMessageSaysWhy() throws Exception {
        TestRunner.Suite suite =
                load(
                        cases(
                                "  - name: no\n    input: { who: x }\n    real: [IssueRefund]\n    tasks: { ServiceTask: ok }\n"));

        assertThat(suite.problems())
                .anyMatch(
                        p ->
                                p.contains(
                                        "real: 'IssueRefund' has effects; a test cannot run it for"
                                                + " real; script its result in tasks:"));
    }

    @Test
    void aNameTheScriptDoesNotRunIsAProblem() throws Exception {
        TestRunner.Suite suite =
                load(
                        cases(
                                "  - name: odd\n    input: { who: x }\n    real: [Nope]\n    tasks: { IssueRefund: ok }\n"));

        assertThat(suite.problems())
                .anyMatch(p -> p.contains("real: 'Nope' is not a task run by the script"));
    }

    @Test
    void aTaskTheClassPathDoesNotHaveStopsAtLoadAndNamesTheClassesOption() {
        String script = SCRIPT.replace("ServiceTask", "MissingTask");

        assertThatThrownBy(
                        () ->
                                Wtest.load(
                                        dir,
                                        script,
                                        cases(
                                                "  - name: gone\n    input: { who: x }\n    real: [MissingTask]\n")))
                .hasMessageContaining("run MissingTask: unknown task")
                .hasMessageContaining("--classes");
    }

    @Test
    void aTaskCannotBeBothScriptedAndReal() throws Exception {
        TestRunner.Suite suite =
                load(
                        cases(
                                "  - name: both\n    input: { who: x }\n    real: [ServiceTask]\n    tasks: { ServiceTask: ok, IssueRefund: ok }\n"));

        assertThat(suite.problems())
                .anyMatch(p -> p.contains("'ServiceTask' is both real and scripted"));
    }

    @Test
    void realMustBeAListOfNames() throws Exception {
        TestRunner.Suite suite =
                load(
                        cases(
                                "  - name: shape\n    input: { who: x }\n    real: ServiceTask\n    tasks: { IssueRefund: ok }\n"));

        assertThat(suite.problems()).anyMatch(p -> p.contains("real: a list of task names"));
    }

    @Test
    void withoutARealListEverythingStaysScriptedAsBefore() throws Exception {
        List<TestRunner.CaseResult> results =
                Wtest.run(
                        dir,
                        SCRIPT,
                        cases(
                                "  - name: scripted\n    input: { who: x }\n    tasks: { ServiceTask: scripted, IssueRefund: ok }\n    expect:\n      result: { value: scripted }\n"));

        assertThat(results).allSatisfy(r -> assertThat(r.failures()).isEmpty());
    }
}
