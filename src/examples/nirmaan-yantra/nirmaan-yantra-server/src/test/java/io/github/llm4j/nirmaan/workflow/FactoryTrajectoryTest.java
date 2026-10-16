package io.github.llm4j.nirmaan.workflow;

import static io.github.llm4j.nirmaan.workflow.Factory.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.testing.ScriptedClient;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.runtime.HumanInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The paths the free mock run never walks. The workflow is the real one, the tasks are the real ones (they run real {@code python3}
 * builds in a temporary workspace), and only the model is scripted. Nothing here costs anything or needs a key.
 */
class FactoryTrajectoryTest {

    private static final String E2E_COMMAND = "python3 e2e-tests/run_e2e.py";
    private final String workspaceBefore = System.getProperty("nirmaan.workspace");

    @TempDir Path workspace;

    @AfterEach
    void restoreWorkspace() {
        if (workspaceBefore != null) System.setProperty("nirmaan.workspace", workspaceBefore);
    }

    private static long requestsFor(ScriptedClient model, String mode) {
        return model.requests().stream().filter(r -> ScriptedClient.currentTask(r).contains(mode)).count();
    }

    private static String requestFor(ScriptedClient model, String mode, int nth) {
        return model.requests().stream().map(ScriptedClient::currentTask).filter(r -> r.contains(mode)).skip(nth).findFirst().orElseThrow();
    }

    private static String read(Run run, String path) throws Exception {
        return Files.readString(run.code().resolve(path));
    }

    @Test
    void aSmoothRunIsOneStraightLineFromIdeaToZip() throws Exception {
        ScriptedClient model = smooth(E2E_COMMAND).build();

        Run run = run(workspace, model, new Person(q -> "yes"));

        assertThat(run.error()).as("how the run ended").isNull();
        assertThat(model.calls()).as("model calls on the smooth path (the cost model in COST.md): Aditi, Rishi, Vihaan red, Drishti, Vihaan green, Vishnu twice").isEqualTo(7);
        assertThat(run.count(TraceEvent.HALT)).as("nothing halted the run").isZero();
        assertThat(run.count(TraceEvent.REWIND)).as("there are no rewinds any more").isZero();
        assertThat(run.person().questions).as("nobody had to be asked anything").isEmpty();
        assertThat(read(run, "add.py")).isEqualTo(ADD_RIGHT);
        assertThat(read(run, "test_add.py")).as("the test is exactly what Vihaan wrote first").isEqualTo(TEST_ADD);
        assertThat(read(run, "APPROVAL.md")).contains("Vishnu approved");
        assertThat(read(run, "QA_REPORT.md")).contains("PASSED");
        try (ZipFile zip = new ZipFile(workspace.resolve("t1").resolve("t1_FINAL.zip").toFile())) {
            assertThat(zip.stream().map(e -> e.getName())).contains("add.py", "test_add.py", "SPEC.md", "PRD.md", "APPROVAL.md", "e2e-tests/run_e2e.py");
        }
        List<String> tasks = run.tasks();
        assertThat(tasks.indexOf("SaveSpec")).isLessThan(tasks.indexOf("ReadCode"));
        assertThat(tasks.lastIndexOf("PackageProject")).isEqualTo(tasks.size() - 1);
        assertThat(requestsFor(model, "STAGE: first")).isEqualTo(1);
        assertThat(requestsFor(model, "STAGE: final")).isEqualTo(1);
    }

    @Test
    void theOnlyLoopIsFixAndItStopsAsSoonAsTheCommandPasses() throws Exception {
        ScriptedClient model = smooth(E2E_COMMAND)
                .when("MODE: GREEN", vihaan(file("add.py", ADD_WRONG)))
                .whenThen("MODE: FIX", vihaan(file("add.py", ADD_WRONG)), vihaan(file("add.py", ADD_WRONG)), vihaan(file("add.py", ADD_RIGHT)))
                .build();

        Run run = run(workspace, model, new Person(q -> "yes"));

        assertThat(run.error()).isNull();
        assertThat(requestsFor(model, "MODE: FIX")).as("two fixes failed, the third passed: no fourth").isEqualTo(3);
        assertThat(run.person().questions).as("it passed within its three fixes, so nobody was asked").isEmpty();
        assertThat(read(run, "add.py")).isEqualTo(ADD_RIGHT);
        assertThat(run.count(TraceEvent.REWIND)).isZero();
    }

    @Test
    void aFixCannotRewriteATestAndItLearnsWhyOnItsNextAttempt() throws Exception {
        ScriptedClient model = smooth(E2E_COMMAND)
                .when("MODE: GREEN", vihaan(file("add.py", ADD_WRONG)))
                .whenThen("MODE: FIX",
                        // 1: tries to get green by gutting the test (and fixing the code in the same batch)
                        vihaan(file("test_add.py", "import unittest\nclass T(unittest.TestCase):\n    def test(self):\n        self.assertTrue(True)\n"), file("add.py", ADD_RIGHT)),
                        // 2: does what the refusal said
                        vihaan(file("add.py", ADD_RIGHT)))
                .build();

        Run run = run(workspace, model, new Person(q -> "yes"));

        assertThat(run.error()).isNull();
        assertThat(read(run, "test_add.py")).as("the test was never touched").isEqualTo(TEST_ADD);
        assertThat(requestFor(model, "MODE: FIX", 1)).as("the refusal reaches the next attempt").contains("LAST SAVE PROBLEM: nothing was written").contains("test_add.py");
        assertThat(read(run, "add.py")).isEqualTo(ADD_RIGHT);
    }

    @Test
    void whenThreeFixesAreNotEnoughAPersonDecidesWhetherToGoOn() throws Exception {
        ScriptedClient stuck = smooth(E2E_COMMAND)
                .when("MODE: GREEN", vihaan(file("add.py", ADD_WRONG)))
                .when("MODE: FIX", vihaan(file("add.py", ADD_WRONG)))
                .build();
        Person stop = new Person(q -> "no");

        Run stopped = run(workspace.resolve("a"), stuck, stop);

        assertThat(requestsFor(stuck, "MODE: FIX")).as("exactly three fixes, never a fourth").isEqualTo(3);
        assertThat(stop.questions).hasSize(1);
        assertThat(stop.questions.get(0)).contains("still fails after 3 fixes").contains("Answer exactly: yes or no");
        assertThat(stopped.error()).as("a no stops the factory").isNotNull();
        assertThat(String.valueOf(stopped.error().getMessage())).contains("chose to stop");
        assertThat(stopped.tasksNamed("PackageProject")).isZero();

        ScriptedClient stuck2 = smooth(E2E_COMMAND)
                .when("MODE: GREEN", vihaan(file("add.py", ADD_WRONG)))
                .when("MODE: FIX", vihaan(file("add.py", ADD_WRONG)))
                .build();
        Person go = new Person(q -> "yes");
        Run released = run(workspace.resolve("b"), stuck2, go);

        assertThat(released.error()).as("a yes lets it through").isNull();
        assertThat(released.tasksNamed("PackageProject")).isEqualTo(1);
        assertThat(released.events().stream().filter(e -> e.type().equals(TraceEvent.NOTE)).map(TraceEvent::text).toList())
                .as("the final note says a person overrode a failure").anyMatch(t -> t.contains("tests yes"));
    }

    @Test
    void theEndToEndSuiteCannotBeEditedByTheDeveloperToMakeItPass() throws Exception {
        String wrongSuite = E2E.replace("'5'", "'6'");   // Drishti's suite expects the wrong answer
        ScriptedClient model = smooth(E2E_COMMAND)
                .when("MODE: WRITE", drishti(file("e2e-tests/run_e2e.py", wrongSuite)))
                .when("FAILED COMMAND: python3 e2e-tests", vihaan(file("e2e-tests/run_e2e.py", E2E), file("add.py", ADD_RIGHT)))
                .build();
        Person stop = new Person(q -> "no");

        Run run = run(workspace, model, stop);

        assertThat(read(run, "e2e-tests/run_e2e.py")).as("Vihaan's attempts to rewrite the suite were refused").isEqualTo(wrongSuite);
        assertThat(requestFor(model, "FAILED COMMAND: python3 e2e-tests", 1)).contains("end to end suite belongs to the QA engineer");
        assertThat(requestsFor(model, "FAILED COMMAND: python3 e2e-tests")).isEqualTo(3);
        assertThat(String.valueOf(run.error().getMessage())).contains("chose to stop");
        assertThat(run.tasksNamed("PackageProject")).isZero();
    }

    @Test
    void aRejectedFirstReviewGetsOnePolishAndTheTestsAreRunAgain() throws Exception {
        ScriptedClient model = smooth(E2E_COMMAND)
                .whenThen("STAGE:", verdict("REJECTED"), verdict("APPROVED"))
                .when("MODE: REFACTOR", vihaan(file("add.py", ADD_RIGHT + "# polished\n")))
                .build();

        Run run = run(workspace, model, new Person(q -> "yes"));

        assertThat(run.error()).isNull();
        assertThat(requestsFor(model, "MODE: REFACTOR")).isEqualTo(1);
        assertThat(requestFor(model, "MODE: REFACTOR", 0)).contains("looked at it: REJECTED");
        assertThat(read(run, "add.py")).endsWith("# polished\n");
        assertThat(model.calls()).as("the smooth 7 plus the polish").isEqualTo(8);
    }

    @Test
    void ifVishnuRefusesTheFinalReleaseAPersonDecidesAndTheApprovalSaysSo() throws Exception {
        Person no = new Person(q -> "no");
        Run refused = run(workspace.resolve("a"), smooth(E2E_COMMAND).whenThen("STAGE:", verdict("APPROVED"), verdict("REJECTED")).build(), no);

        assertThat(no.questions).anyMatch(q -> q.contains("Vishnu does not approve the release"));
        assertThat(String.valueOf(refused.error().getMessage())).contains("Release refused");
        assertThat(refused.tasksNamed("PackageProject")).isZero();

        Person yes = new Person(q -> "yes");
        Run overridden = run(workspace.resolve("b"), smooth(E2E_COMMAND).whenThen("STAGE:", verdict("APPROVED"), verdict("REJECTED")).build(), yes);

        assertThat(overridden.error()).isNull();
        assertThat(read(overridden, "APPROVAL.md")).as("the approval does not claim Vishnu approved").contains("did NOT approve").contains("Released anyway by a person");
        assertThat(overridden.tasksNamed("PackageProject")).isEqualTo(1);
    }

    @Test
    void aCommandThePolicyCannotVouchForWaitsForAPerson_andRunsOnlyIfTheySayYes() throws Exception {
        String odd = "python3 -c \"open('ran.txt', 'w').write('x'); import sys; sys.exit(0)\"";

        Person yes = new Person(q -> "yes");
        Run approved = run(workspace.resolve("a"), smooth(odd).build(), yes);
        Person no = new Person(q -> "no");
        Run refused = run(workspace.resolve("b"), smooth(odd).build(), no);

        assertThat(approved.error()).isNull();
        assertThat(yes.kinds).as("it was asked as an approval").contains(HumanInterface.Hints.Kind.APPROVAL);
        assertThat(yes.questions).anyMatch(q -> q.contains("RunCommand"));
        assertThat(approved.code().resolve("ran.txt")).exists();

        assertThat(no.questions).anyMatch(q -> q.contains("RunCommand"));
        assertThat(refused.error()).as("a refusal ends the run").isNotNull();
        assertThat(String.valueOf(refused.error().getMessage())).containsIgnoringCase("approve");
        assertThat(refused.code().resolve("ran.txt")).as("the command never ran").doesNotExist();
        assertThat(workspace.resolve("b").resolve("t1").resolve("t1_FINAL.zip")).as("nothing was released").doesNotExist();
    }

    @Test
    void whenTheRunBudgetIsUsedUpAPersonIsAskedInsteadOfTheRunJustDying() throws Exception {
        Person decliner = new Person(q -> "no");
        Run stopped = run(workspace.resolve("a"), smooth(E2E_COMMAND).build(), decliner, 2500L);

        assertThat(decliner.questions).as("a person was asked about the budget").anyMatch(q -> q.toLowerCase().contains("budget"));
        assertThat(stopped.error()).as("a 'no' stops the run cleanly").isNotNull();
        assertThat(String.valueOf(stopped.error())).containsIgnoringCase("budget");
        assertThat(stopped.code().resolve("PRD.md")).as("what was already paid for is kept").exists();

        Person extender = new Person(q -> "yes");
        Run extended = run(workspace.resolve("b"), smooth(E2E_COMMAND).build(), extender, 2500L);

        assertThat(extender.questions).anyMatch(q -> q.toLowerCase().contains("budget"));
        assertThat(extended.error()).as("a 'yes' lets the run carry on to the end").isNull();
        assertThat(extended.tasksNamed("PackageProject")).isEqualTo(1);
    }

    @Test
    void aFailingCleanBuildCostsNoModelCallsAndAPersonDecides() throws Exception {
        java.util.function.Supplier<ScriptedClient> model = () -> smooth(E2E_COMMAND).withBuildCommand("bash build.sh")
                .when("MODE: GREEN", vihaan(file("add.py", ADD_RIGHT), file("build.sh", "echo 'cannot package' >&2\nexit 1\n")))
                .build();
        ScriptedClient asked = model.get();
        Person no = new Person(q -> "no");

        Run stopped = run(workspace.resolve("a"), asked, no);

        assertThat(no.questions).hasSize(1);
        assertThat(no.questions.get(0)).contains("The clean build fails (probably packaging)").contains("cannot package");
        assertThat(String.valueOf(stopped.error().getMessage())).contains("clean build fails and a person chose to stop");
        assertThat(requestsFor(asked, "MODE: FIX")).as("no model was paid to fix a build").isZero();
        assertThat(asked.calls()).as("Aditi, Rishi, Vihaan red, Drishti, Vihaan green, Vishnu's first review, and then nothing").isEqualTo(6);
        assertThat(stopped.tasksNamed("PackageProject")).isZero();

        ScriptedClient carried = model.get();
        Person yes = new Person(q -> "yes");
        Run released = run(workspace.resolve("b"), carried, yes);

        assertThat(released.error()).isNull();
        assertThat(requestsFor(carried, "MODE: FIX")).isZero();
        assertThat(read(released, "QA_REPORT.md")).contains("Clean build: failed");
        assertThat(released.tasksNamed("PackageProject")).isEqualTo(1);
    }
}
