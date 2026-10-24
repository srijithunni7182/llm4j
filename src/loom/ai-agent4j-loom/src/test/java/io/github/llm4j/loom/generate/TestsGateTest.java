package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G3 and G4 of spec 12: the project's own tests pass and reach enough of the script. */
class TestsGateTest {

    @TempDir Path dir;

    static final Writer.Stamp STAMP = new Writer.Stamp("t", "d");

    private GateResult run(Gate gate, Map<String, String> files, Writer.Layout layout)
            throws Exception {
        try (Workspace ws = Writer.render(files, GenerateFixtures.plan(), layout, STAMP, dir)) {
            return gate.run(ws, GenerateFixtures.plan());
        }
    }

    private Map<String, String> with(String tests) {
        Map<String, String> f = new LinkedHashMap<>(GenerateFixtures.files());
        if (tests == null) f.remove("tests/main.test.yaml");
        else f.put("tests/main.test.yaml", tests);
        return f;
    }

    @Test
    void theGeneratedTestsPassAndCoverTheScriptInBothLayouts() throws Exception {
        for (Writer.Layout layout : new Writer.Layout[] {Writer.Layout.FLAT, Writer.Layout.MAVEN}) {
            assertThat(run(TestsGate.passing(), with(GenerateFixtures.TESTS), layout).problems())
                    .as(layout + " G3")
                    .isEmpty();
            assertThat(run(TestsGate.coverage(80), with(GenerateFixtures.TESTS), layout).problems())
                    .as(layout + " G4")
                    .isEmpty();
        }
    }

    @Test
    void aFailingAssertionIsFedBackWithTheCaseAndWhatDiffered() throws Exception {
        String wrong =
                GenerateFixtures.TESTS.replace(
                        "calls: { Drafter: 1, Notifier: 1 }", "calls: { Drafter: 1, Notifier: 3 }");
        GateResult r = run(TestsGate.passing(), with(wrong), Writer.Layout.FLAT);
        assertThat(r.passed()).isFalse();
        assertThat(r.problems()).hasSize(1);
        assertThat(r.problems().get(0).text())
                .contains("main.test.yaml")
                .contains("case \"approved reply is sent\" failed")
                .contains("Notifier");
        assertThat(r.feedback()).contains("Gate G3 (tests) failed");
    }

    @Test
    void testsThatDoNotFitTheScriptAreReportedNotRun() throws Exception {
        String renamed =
                GenerateFixtures.TESTS.replace(
                        "Drafter: \"We are sorry.\"", "Draftr: \"We are sorry.\"");
        GateResult r = run(TestsGate.passing(), with(renamed), Writer.Layout.FLAT);
        assertThat(r.passed()).isFalse();
        assertThat(r.problems().get(0).message()).contains("Draftr");
    }

    @Test
    void noTestsAtAllIsAFailureThatSaysWhatToWrite() throws Exception {
        GateResult r = run(TestsGate.passing(), with(null), Writer.Layout.FLAT);
        assertThat(r.problems().get(0).message())
                .contains("no tests/*.test.yaml")
                .contains("every route and every limit");
        assertThat(run(TestsGate.coverage(80), with(null), Writer.Layout.FLAT).passed()).isFalse();
    }

    @Test
    void aFileWithNoCasesAndAFileThatIsNotYamlAreFailuresNotCrashes() throws Exception {
        assertThat(
                        run(
                                        TestsGate.passing(),
                                        with("script: main.loom\nworkflow: Main\ncases: []\n"),
                                        Writer.Layout.FLAT)
                                .problems()
                                .get(0)
                                .message())
                .contains("at least one case");
        GateResult broken = run(TestsGate.passing(), with("::: not yaml : ["), Writer.Layout.FLAT);
        assertThat(broken.passed()).isFalse();
    }

    @Test
    void coverageNamesTheStepsNoCaseReachesWithAHint() throws Exception {
        // only the refused route: the sending and the failure handlers are never reached
        String thin =
                "script: main.loom\nworkflow: Main\ncases:\n  - name: refused\n    input: { ticket_body: \"t\" }\n    models: { Drafter: \"We are sorry.\" }\n    humans: [\"no\"]\n";
        GateResult r = run(TestsGate.coverage(80), with(thin), Writer.Layout.FLAT);
        assertThat(r.passed()).isFalse();
        assertThat(r.problems().get(0).message())
                .contains("reach")
                .contains("% of the steps")
                .contains("at least 80%");
        assertThat(r.problems())
                .anyMatch(
                        p ->
                                p.message().startsWith("not reached: ")
                                        && p.message().contains("Notifier"));
    }

    @Test
    void failingCasesDoNotCountTowardsCoverageAndAreG3sToReport() throws Exception {
        // the only case that reaches the sending fails, so the sending counts as not reached
        String wrong =
                "script: main.loom\nworkflow: Main\ncases:\n  - name: sent\n    input: { ticket_body: \"t\" }\n    models: { Drafter: \"We are sorry.\", Notifier: \"Sent.\" }\n    humans: [\"yes\"]\n    expect:\n      calls: { Notifier: 9 }\n";
        assertThat(run(TestsGate.passing(), with(wrong), Writer.Layout.FLAT).passed()).isFalse();
        GateResult c = run(TestsGate.coverage(100), with(wrong), Writer.Layout.FLAT);
        assertThat(c.passed()).isFalse();
        assertThat(c.problems())
                .anyMatch(
                        p ->
                                p.message().startsWith("not reached: ")
                                        && p.message().contains("Notifier"));
    }

    @Test
    void theGateRunsOnlyScriptedCollaboratorsAndSpendsNothing() throws Exception {
        // a model that is not scripted fails the case rather than being called: strict by default
        String unscripted =
                "script: main.loom\nworkflow: Main\ncases:\n  - name: no models given\n    input: { ticket_body: \"t\" }\n    humans: [\"no\"]\n";
        GateResult r = run(TestsGate.passing(), with(unscripted), Writer.Layout.FLAT);
        assertThat(r.passed()).isFalse();
        assertThat(r.problems().get(0).message()).contains("Drafter");
    }

    @Test
    void aToolWhoseAddressIsReadFromTheEnvironmentIsNotAFailureUnderTest() throws Exception {
        Map<String, String> f = with(GenerateFixtures.TESTS);
        f.put(
                "main.loom",
                GenerateFixtures.SCRIPT.replace(
                        "tool Hook { use: webhook  url: env.HOOK_URL }",
                        "tool Hook { use: webhook  url: env.HOOK_URL }\n"
                                + "tool Mailer { use: email  host: env.SMTP_HOST  username: env.SMTP_USER  password: env.SMTP_PASS  from: env.MAIL_FROM  to: env.MAIL_TO  security: \"STARTTLS\" }"));
        assertThat(run(TestsGate.passing(), f, Writer.Layout.FLAT).problems()).isEmpty();
    }

    @Test
    void aStepNamedWrongIsAnsweredWithTheStepsThatExist() throws Exception {
        String wrong =
                GenerateFixtures.TESTS.replace(
                        "calls: { Drafter: 1, Notifier: 1 }",
                        "calls: { Drafter: 1, Notifier: 1 }\n      never: [\"delegate Nobody\"]");
        GateResult r = run(TestsGate.passing(), with(wrong), Writer.Layout.FLAT);
        assertThat(r.passed()).isFalse();
        assertThat(r.feedback())
                .contains("is not a step")
                .contains("The steps a case can name")
                .contains("delegate Drafter");
    }
}
