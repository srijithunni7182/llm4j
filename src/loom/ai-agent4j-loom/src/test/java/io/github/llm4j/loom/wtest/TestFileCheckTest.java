package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2, R3, R4, R19, R20 of spec 11: a test file is checked against the script before anything runs,
 * and each problem names the file and the case and the nearest valid name.
 */
class TestFileCheckTest {

    @TempDir Path dir;

    List<String> problems(String yaml) throws Exception {
        return Wtest.load(dir, Wtest.TRIAGE, yaml).problems();
    }

    static String one(String body) {
        return Wtest.triageCases("  - name: c\n    input: { message: hi }\n" + body);
    }

    @Test
    void aSoundFileHasNoProblems() throws Exception {
        assertThat(
                        problems(
                                one(
                                        "    models: { Intent: { kind: refund, urgent: 'no' }, Escalator: ok }\n    expect: { calls: { Escalator: 1 }, route: [ classify Intent ] }")))
                .isEmpty();
    }

    @Test
    void anAgentTheScriptDoesNotHaveIsRefusedWithTheNearestName() throws Exception {
        assertThat(problems(one("    models: { Replyer: hi }")))
                .singleElement()
                .asString()
                .contains("main.test.yaml")
                .contains("case \"c\"")
                .contains("'Replyer' is not an agent or classifier")
                .contains("did you mean 'Replier'");
    }

    @Test
    void aRenamedAgentBreaksTheFileCheckAndNamesTheNewName() throws Exception {
        String renamed = Wtest.TRIAGE.replace("Replier", "Repliers");
        Wtest.write(
                dir,
                renamed,
                one("    models: { Replier: hi, Intent: { kind: refund, urgent: 'no' } }"));
        var suite = TestRunner.load(dir.resolve("main.loom"), List.of(dir.resolve("tests")));
        assertThat(suite.problems())
                .anyMatch(p -> p.contains("'Replier'") && p.contains("did you mean 'Repliers'"));
    }

    @Test
    void aClassifierAnswerOutsideItsLabelsOrAboutAnUnknownQuestionIsRefused() throws Exception {
        List<String> p = problems(one("    models: { Intent: { kind: complaint, urgnt: 'no' } }"));
        assertThat(p)
                .anyMatch(
                        x ->
                                x.contains(
                                        "models.Intent.kind: 'complaint' is not one of [order_status, refund, other]"))
                .anyMatch(
                        x ->
                                x.contains("'urgnt' is not a question of the classifier")
                                        && x.contains("did you mean 'urgent'"))
                .anyMatch(x -> x.contains("no answer for the question 'urgent'"));
    }

    @Test
    void unsureIsAllowedOnlyWhenTheClassifierEscalates() throws Exception {
        assertThat(problems(one("    models: { Intent: { kind: unsure, urgent: 'no' } }")))
                .anyMatch(x -> x.contains("'unsure' is not one of"));
        String escalating =
                Wtest.TRIAGE.replace(
                        "ask urgent:", "confidence at least 0.8 else escalate\n    ask urgent:");
        var suite =
                Wtest.load(
                        dir,
                        escalating,
                        one("    models: { Intent: { kind: unsure, urgent: 'no' } }"));
        assertThat(suite.problems()).isEmpty();
    }

    @Test
    void aStepThatIsNotInTheScriptIsRefusedAndTheKnownOnesAreAccepted() throws Exception {
        assertThat(problems(one("    expect: { route: [ delegate Replyer ] }")))
                .singleElement()
                .asString()
                .contains("expect.route: 'delegate Replyer' is not a step of the script")
                .contains("did you mean 'delegate Replier'")
                .contains("weave test --steps");
        assertThat(
                        problems(
                                one(
                                        "    expect: { route: [ intentclassify Intent, human_prompt, n3, delegate Replier ] }")))
                .anyMatch(x -> x.contains("'human_prompt'"));
        assertThat(
                        problems(
                                one(
                                        "    expect: { route: [ intentclassify Intent, delegate Replier, n3, note ] }")))
                .isEmpty();
    }

    @Test
    void callsMayNameZeroButOnlyThingsThatExist() throws Exception {
        assertThat(problems(one("    expect: { calls: { Replier: 0 } }"))).isEmpty();
        assertThat(problems(one("    expect: { calls: { Ghost: 0 } }")))
                .singleElement()
                .asString()
                .contains("'Ghost' is not an agent, classifier, tool or task");
    }

    @Test
    void inputsMustMatchTheWorkflowsParameters() throws Exception {
        assertThat(
                        problems(
                                "script: ../main.loom\ncases:\n  - name: c\n    input: { mesage: hi }\n"))
                .anyMatch(
                        x ->
                                x.contains("input 'mesage' is not a parameter of Main")
                                        && x.contains("did you mean 'message'"))
                .anyMatch(x -> x.contains("input 'message' is missing"));
    }

    @Test
    void unknownKeysAreRefusedWithAHint() throws Exception {
        assertThat(problems(one("    model: { Replier: hi }")))
                .singleElement()
                .asString()
                .contains("unknown key 'model'")
                .contains("did you mean 'models'");
        assertThat(problems(one("    expect: { rout: [ note ] }")))
                .singleElement()
                .asString()
                .contains("expect: unknown key 'rout'")
                .contains("did you mean 'route'");
    }

    @Test
    void duplicateCaseNamesAndABadFinalAreRefused() throws Exception {
        String twice =
                Wtest.triageCases(
                        "  - name: c\n    input: { message: a }",
                        "  - name: c\n    input: { message: b }\n    expect: { final: done }");
        assertThat(problems(twice))
                .anyMatch(x -> x.contains("this name is used by another case"))
                .anyMatch(x -> x.contains("expect.final: one of"));
    }

    @Test
    void whichWorkflowMustBeSaidWhenThereAreSeveral() throws Exception {
        String two = Wtest.TRIAGE + "\nworkflow Other(message) { note \"x\" }\n";
        var suite =
                Wtest.load(
                        dir,
                        two,
                        "script: ../main.loom\ncases:\n  - name: c\n    input: { message: hi }\n");
        assertThat(suite.problems())
                .anyMatch(
                        x ->
                                x.contains("which workflow?")
                                        && x.contains("Main")
                                        && x.contains("Other"));
        var named =
                Wtest.load(
                        dir,
                        two,
                        "script: ../main.loom\nworkflow: Mian\ncases:\n  - name: c\n    input: { message: hi }\n");
        assertThat(named.problems())
                .anyMatch(x -> x.contains("workflow 'Mian'") && x.contains("did you mean 'Main'"));
    }

    @Test
    void badMatchersAreRefusedWhenTheFileIsChecked() throws Exception {
        assertThat(problems(one("    expect: { result: { matches: '(' } }")))
                .singleElement()
                .asString()
                .contains("expect.result")
                .contains("does not compile");
    }

    @Test
    void aBrokenFileIsReportedWithItsNameAndNothingElse() throws Exception {
        Wtest.write(dir, Wtest.TRIAGE, "cases: [ : :\n");
        var suite = TestRunner.load(dir.resolve("main.loom"), List.of(dir.resolve("tests")));
        assertThat(suite.problems()).singleElement().asString().contains("main.test.yaml");
        Files.writeString(dir.resolve("tests/main.test.yaml"), "cases:\n  - input: {}\n");
        suite = TestRunner.load(dir.resolve("main.loom"), List.of(dir.resolve("tests")));
        assertThat(suite.problems()).anyMatch(x -> x.contains("case 1 has no name"));
    }

    @Test
    void defaultsMergeIntoEveryCaseMapsByKeyAndListsReplaced() throws Exception {
        String yaml =
                """
                script: ../main.loom
                workflow: Main
                defaults:
                  tags: [ smoke ]
                  models:
                    Replier: from defaults
                    Escalator: also from defaults
                cases:
                  - name: a
                    input: { message: hi }
                    tags: [ failure ]
                    models:
                      Replier: from the case
                """;
        var suite = Wtest.load(dir, Wtest.TRIAGE, yaml);
        var c = suite.files().get(0).cases().get(0);
        assertThat(c.get("models").get("Replier").asText()).isEqualTo("from the case");
        assertThat(c.get("models").get("Escalator").asText()).isEqualTo("also from defaults");
        assertThat(c.tags()).containsExactly("failure"); // a list is replaced, not appended
        assertThat(c.yaml()).contains("Replier: \"from the case\"");
    }

    @Test
    void stepsAreListedWithTheirIdsLinesAndLabels() throws Exception {
        var suite = Wtest.load(dir, Wtest.TRIAGE, one(""));
        var labels = Labels.known(suite.graph());
        assertThat(labels)
                .contains(
                        "classify Intent",
                        "intentclassify Intent",
                        "delegate Replier",
                        "delegate Escalator",
                        "note",
                        "n1");
        assertThat(suite.graph().workflows().get(0).nodes())
                .anyMatch(n -> n.label().equals("delegate Replier"));
    }

    @Test
    void aScriptThatDoesNotLoadIsAnErrorBeforeAnyCaseRuns() throws Exception {
        Wtest.write(dir, "workflow Main(q) { delegate \"x\" to Nobody -> r }", one(""));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                TestRunner.load(
                                        dir.resolve("main.loom"), List.of(dir.resolve("tests"))))
                .hasMessageContaining("Nobody");
    }
}
