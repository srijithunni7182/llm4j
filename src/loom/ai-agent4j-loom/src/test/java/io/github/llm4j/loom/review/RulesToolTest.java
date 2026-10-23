package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.tools.RulesTool;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R70, R71, R63, R64 of spec 09: the offline {@code weave rules} tools. */
class RulesToolTest {

    @TempDir Path dir;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    RulesTool tool;
    Path acme;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RulesTool(new PrintStream(out, true), new PrintStream(err, true));
        acme = dir.resolve("source/acme-policy");
        Files.createDirectories(acme.resolve("rules"));
        Files.writeString(acme.resolve("pack.yaml"), PackLoaderTest.MANIFEST);
        Files.writeString(acme.resolve("rules/r.yaml"), PackLoaderTest.RULE);
    }

    String out() {
        return out.toString();
    }

    String err() {
        return err.toString();
    }

    @Test
    void listShowsTheCorePackAndSaysWhyARuleIsOff() {
        assertThat(tool.list(dir)).isZero();
        assertThat(out())
                .contains("core@1.5.0")
                .contains("LR01")
                .contains("LM01")
                .contains("off (lens)")
                .contains("LA01 to LA20");
    }

    @Test
    void listShowsProjectOverridesAndPacks() throws Exception {
        Files.writeString(
                dir.resolve("review.properties"), "LR01 = off\nlenses = +maintainability\n");
        assertThat(tool.list(dir)).isZero();
        assertThat(out()).contains("off (project)");
        assertThat(out()).doesNotContain("LM01  Maintainability  Low     off (lens)");
    }

    @Test
    void listFailsClearlyWhenAPackIsMissing() throws Exception {
        Files.writeString(dir.resolve("review.properties"), "packs = nope@1\n");
        assertThat(tool.list(dir)).isEqualTo(2);
        assertThat(err()).contains("nope@1");
    }

    @Test
    void factsPrintsEveryEntityAndTheSettingEscapeHatch() {
        assertThat(tool.facts()).isZero();
        assertThat(out())
                .contains("Fact model 1")
                .contains("agent")
                .contains("step")
                .contains("tool")
                .contains("setting.<key>");
    }

    @Test
    void checkAcceptsASoundPackAndPrintsItsHash() {
        assertThat(tool.check(acme.toString())).isZero();
        assertThat(out())
                .contains("acme-policy@2.0.1 is sound")
                .containsPattern("sha256:[0-9a-f]{64}");
        assertThat(tool.check("core")).isZero();
    }

    @Test
    void checkListsEveryProblemOfABrokenPack() throws Exception {
        Files.writeString(acme.resolve("rules/r.yaml"), "id: ACME-001\nlens: nope\n");
        assertThat(tool.check(acme.toString())).isEqualTo(1);
        assertThat(err()).contains("is not a sound pack").contains("  - ");
    }

    @Test
    void testRunsTheExamplesAndFailsWhenARuleMisbehaves() throws Exception {
        assertThat(tool.test(acme.toString())).isZero();
        assertThat(out()).contains("pass  ACME-001  fires").contains("0 failed");
        out.reset();
        Files.writeString(
                acme.resolve("rules/r.yaml"),
                PackLoaderTest.RULE.replace("is: delegate", "is: loop"));
        assertThat(tool.test(acme.toString())).isEqualTo(1);
        assertThat(out()).contains("FAIL  ACME-001  fires").contains("1 failed");
        assertThat(tool.test("core")).isZero();
    }

    @Test
    void newWritesARuleThatPassesItsOwnExamplesStraightAway() throws Exception {
        assertThat(tool.scaffold("ACME-002", acme)).isZero();
        assertThat(acme.resolve("rules/acme-002.yaml")).exists();
        assertThat(acme.resolve("examples/acme-002-fires.loom")).exists();
        out.reset();
        assertThat(tool.test(acme.toString())).isZero();
        assertThat(out()).contains("ACME-002");
    }

    @Test
    void newRefusesABadIdAnExistingRuleAndAFolderThatIsNotAPack() throws Exception {
        assertThat(tool.scaffold("acme", acme)).isEqualTo(2);
        assertThat(tool.scaffold("ACME-009", dir)).isEqualTo(2);
        assertThat(tool.scaffold("ACME-009", acme)).isZero();
        assertThat(tool.scaffold("ACME-009", acme)).isEqualTo(2);
        assertThat(err())
                .contains("not a rule id")
                .contains("no pack.yaml")
                .contains("already exists");
    }

    @Test
    void addCopiesThePackPinsItAndTheReviewThenAcceptsTheProject() throws Exception {
        Path project = dir.resolve("project");
        Files.createDirectories(project);
        assertThat(tool.add(acme, project)).isZero();
        assertThat(project.resolve("review-rules/acme-policy/pack.yaml")).exists();
        String props = Files.readString(project.resolve("review.properties"));
        assertThat(props).startsWith("packs = core@1, acme-policy@2.0.1 sha256:");
        assertThat(tool.list(project)).isZero();
        assertThat(out()).contains("acme-policy@2.0.1").contains("ACME-001");
    }

    @Test
    void addingANewVersionShowsWhatChangedAndRepinsWithoutLosingOtherSettings() throws Exception {
        Path project = dir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("review.properties"),
                "# mine\nfail-on = high\npacks = core@1, acme-policy@1.0.0   # org rules\n");
        assertThat(tool.add(acme, project)).isZero();
        out.reset();
        Files.writeString(
                acme.resolve("rules/second.yaml"),
                PackLoaderTest.RULE.replace("ACME-001", "ACME-005"));
        Files.writeString(
                acme.resolve("pack.yaml"), PackLoaderTest.MANIFEST.replace("2.0.1", "2.1.0"));
        assertThat(tool.add(acme, project)).isZero();
        assertThat(out())
                .contains("Replacing acme-policy@2.0.1 with acme-policy@2.1.0")
                .contains("added    rules/second.yaml")
                .contains("changed  pack.yaml")
                .contains("new rule ACME-005");
        String props = Files.readString(project.resolve("review.properties"));
        assertThat(props)
                .contains("# mine")
                .contains("fail-on = high")
                .contains("acme-policy@2.1.0 sha256:")
                .contains("# org rules")
                .doesNotContain("acme-policy@1.0.0");
    }

    @Test
    void addRefusesAnUnsoundPackAndTheCorePack() throws Exception {
        Files.writeString(acme.resolve("rules/r.yaml"), "id: x\n");
        Path project = dir.resolve("project");
        Files.createDirectories(project);
        assertThat(tool.add(acme, project)).isEqualTo(1);
        assertThat(project.resolve("review-rules")).doesNotExist();
        Files.writeString(acme.resolve("rules/r.yaml"), PackLoaderTest.RULE);
        Files.writeString(
                acme.resolve("pack.yaml"),
                "name: core\nversion: 1.0.0\nfactModel: 1\nrulesSchema: 1\n");
        assertThat(tool.add(acme, project)).isEqualTo(1);
        assertThat(err()).contains("a core rule id is L");
        assertThat(project.resolve("review-rules")).doesNotExist();
        Files.writeString(
                acme.resolve("rules/r.yaml"), PackLoaderTest.RULE.replace("ACME-001", "LZ01"));
        assertThat(tool.add(acme, project)).isEqualTo(2);
        assertThat(err()).contains("cannot be replaced");
    }

    @Test
    void explainAtShowsEachConditionWithTheValueItSawAndTheOutcome() throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(
                script,
                "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) {\n  delegate \"{q}\" to A -> r\n  note \"{r}\"\n}\n");
        assertThat(tool.explainAt("LR01", script + ":3")).isZero();
        assertThat(out())
                .contains("LR01 at ")
                .contains("line 3")
                .containsAnyOf("fires here", "does not fire here");
    }

    @Test
    void explainAtReportsABadPlaceAnUnknownRuleAndAMissingFile() throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(
                script,
                "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { note \"x\" }\n");
        assertThat(tool.explainAt("LR01", "main.loom")).isEqualTo(2);
        assertThat(tool.explainAt("LR01", dir.resolve("nope.loom") + ":1")).isEqualTo(2);
        assertThat(tool.explainAt("LR99", script + ":1")).isEqualTo(2);
        assertThat(tool.explainAt("LA01", script + ":1")).isEqualTo(2);
        assertThat(err())
                .contains("file:line")
                .contains("does not exist")
                .contains("did you mean 'LR01'")
                .contains("Java");
    }
}
