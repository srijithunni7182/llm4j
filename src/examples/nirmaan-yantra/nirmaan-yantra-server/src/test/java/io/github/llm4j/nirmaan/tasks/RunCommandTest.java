package io.github.llm4j.nirmaan.tasks;

import static io.github.llm4j.nirmaan.tasks.TaskTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dhruv's task, against real programs the build machine always has: java and sh. */
class RunCommandTest {

    @TempDir Path workspace;
    Path code;

    @BeforeEach
    void setUp() {
        TaskTestSupport.newWorkspace(workspace);
        code = Sandbox.code("p1");
    }

    private static Map<String, Object> args(String command) {
        return Map.of("project", "p1", "command", command, "label", "t");
    }

    @Test
    void aCommandThatExitsZeroPasses() throws Exception {
        var result = run(new RunCommand(30), args("java -version"));

        assertThat(result.outcome()).isEqualTo("passed");
        assertThat(String.valueOf(result.value())).contains("$ java -version");
        assertThat(Sandbox.meta("p1").resolve("logs/t.log")).exists();
    }

    @Test
    void aFailingCommandFailsWithItsOutputAsTheValue() throws Exception {
        Files.writeString(code.resolve("fail.sh"), "echo broken >&2\nexit 3\n");

        var result = run(new RunCommand(30), args("bash fail.sh"));

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.toMap()).containsEntry("exit_code", 3);
        assertThat(String.valueOf(result.value())).contains("broken");
    }

    @Test
    void stepsJoinedWithAndAndStopAtTheFirstFailure() throws Exception {
        Files.writeString(code.resolve("fail.sh"), "exit 1\n");
        Files.writeString(code.resolve("never.sh"), "echo SHOULD-NOT-RUN\n");

        var result = run(new RunCommand(30), args("bash fail.sh && bash never.sh"));

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(String.valueOf(result.value())).doesNotContain("SHOULD-NOT-RUN");
    }

    @Test
    void aBlockedCommandIsRefusedAndNeverStarted() throws Exception {
        Files.writeString(code.resolve("keep.txt"), "keep me");

        var result = run(new RunCommand(30), args("rm -rf keep.txt"));

        assertThat(result.outcome()).isEqualTo("refused");
        assertThat(code.resolve("keep.txt")).exists();
    }

    @Test
    void shellSyntaxIsNotInterpreted() throws Exception {
        // A person has approved this one, so it runs; with no shell, the semicolon is just a character in an argument.
        var result = run(new RunCommand(30), args("java -version ; touch injected.txt"));

        assertThat(code.resolve("injected.txt")).doesNotExist();
        assertThat(result.outcome()).as("java simply ignores the extra words").isEqualTo("passed");
    }

    @Test
    void aHangingCommandIsStoppedAtTheTimeLimit() throws Exception {
        Files.writeString(code.resolve("hang.sh"), "sleep 30\n");

        long started = System.nanoTime();
        var result = run(new RunCommand(1), args("bash hang.sh"));

        assertThat(result.outcome()).isEqualTo("timed_out");
        assertThat((System.nanoTime() - started) / 1_000_000_000).isLessThan(15);
    }

    @Test
    void theCodeBeingBuiltDoesNotSeeTheFactorysSecretsOrTheRealHome() throws Exception {
        assertThat(System.getenv("NIRMAAN_TEST_SECRET")).as("the test JVM really has the secret (see the pom)").isEqualTo("hunter2");
        Files.writeString(code.resolve("show.sh"), "echo KEY=[$NIRMAAN_TEST_SECRET]\necho HOME=[$HOME]\necho UH=[$JAVA_TOOL_OPTIONS]\n");

        var result = run(new RunCommand(30), args("bash show.sh"));

        String out = String.valueOf(result.value());
        assertThat(result.outcome()).isEqualTo("passed");
        assertThat(out).contains("KEY=[]").doesNotContain("hunter2");
        assertThat(out).contains("HOME=[" + workspace.resolve(".toolhome") + "]").doesNotContain(System.getProperty("user.home") + "]");
        assertThat(out).contains("-Duser.home=" + workspace.resolve(".toolhome"));
    }

    @Test
    void javaToolsGetThePrivateHomeToo() throws Exception {
        Files.writeString(code.resolve("Home.java"), "public class Home { public static void main(String[] a) { System.out.println(\"UH=\" + System.getProperty(\"user.home\")); } }\n");

        var result = run(new RunCommand(60), args("java Home.java"));

        assertThat(String.valueOf(result.value())).contains("UH=" + workspace.resolve(".toolhome"));
    }

    @Test
    void aDeploymentThatRequiresAContainerRefusesToRunLocally() throws Exception {
        var strict = new SandboxConfig("local", "img", "bridge", "4g", "2", "", true);

        var result = run(new RunCommand(30, strict), args("java -version"));

        assertThat(result.outcome()).isEqualTo("refused");
        assertThat(result.reason()).contains("container");
    }

    @Test
    void noneMeansThereIsNothingToRun() throws Exception {
        assertThat(run(new RunCommand(30), args("None")).outcome()).isEqualTo("passed");
    }

    @Test
    void onlyACommandThePolicyCannotVouchForAsksAPerson() {
        RunCommand task = new RunCommand(30);

        assertThat(task.requiresApproval(Map.of("command", "mvn test"))).isFalse();
        assertThat(task.requiresApproval(Map.of("command", "None"))).isFalse();
        assertThat(task.requiresApproval(Map.of("command", "rm -rf x"))).as("blocked is refused, not asked").isFalse();
        assertThat(task.requiresApproval(Map.of("command", "curl http://example.com"))).isTrue();
        assertThat(task.requiresApproval(Map.of("command", "mvn test | tee x"))).isTrue();
    }
}
