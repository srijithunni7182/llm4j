package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.generate.GenerateFixtures;
import io.github.llm4j.loom.generate.PatchSet;
import io.github.llm4j.loom.generate.PlanJson;
import io.github.llm4j.loom.generate.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R20 to R24 of spec 12 through the command: a change is a diff, held to the gates, applied only
 * when asked.
 */
class GenerateEditTest {

    @TempDir Path dir;

    private Path project(boolean withPlan) throws Exception {
        Path p = Files.createDirectory(dir.resolve("proj" + (withPlan ? "-plan" : "")));
        Map<String, String> files = new LinkedHashMap<>(GenerateFixtures.files());
        if (withPlan) {
            files.put("plan.json", PlanJson.write(GenerateFixtures.plan()));
        }
        try (var ws =
                Writer.render(
                        files,
                        GenerateFixtures.plan(),
                        Writer.Layout.FLAT,
                        new Writer.Stamp("t", "d"),
                        dir)) {
            assertThat(ws.commit(p, false).ok()).isTrue();
        }
        return p;
    }

    private static String reply(String path, String search, String replace) {
        return "=== edit: "
                + path
                + " ===\n<<<<<<< SEARCH\n"
                + search
                + "\n=======\n"
                + replace
                + "\n>>>>>>> REPLACE\n=== end ===\n";
    }

    private GenerateCommandTest.Out edit(
            Path project, String[] replies, String request, String... args) {
        String[] all = new String[args.length + 5];
        all[0] = request;
        all[1] = "--edit";
        all[2] = project.resolve("main.loom").toString();
        all[3] = "--model";
        all[4] = "m";
        System.arraycopy(args, 0, all, 5, args.length);
        return GenerateCommandTest.generate(GenerateCommandTest.sequence(replies), all);
    }

    @Test
    void aSmallChangeIsShownAsADiffAndNothingIsWrittenUntilApplied() throws Exception {
        Path p = project(false);
        String before = Files.readString(p.resolve("main.loom"));
        var o =
                edit(
                        p,
                        new String[] {reply("main.loom", "temperature: 0.3", "temperature: 0.1")},
                        "make the drafter more careful");
        assertThat(o.err()).isEmpty();
        assertThat(o.code()).as(o.out()).isZero();
        assertThat(o.out())
                .contains("-", "temperature: 0.3")
                .contains("temperature: 0.1")
                .contains("✓ G1 check")
                .contains("✓ G2 review delta")
                .contains("✓ G3 tests")
                .contains("Nothing was changed. Apply it with --apply");
        assertThat(Files.readString(p.resolve("main.loom"))).isEqualTo(before);
    }

    @Test
    void applyWritesExactlyTheChangedLinesAndLeavesTheRestOfTheFileAlone() throws Exception {
        Path p = project(false);
        String before = Files.readString(p.resolve("main.loom"));
        var o =
                edit(
                        p,
                        new String[] {reply("main.loom", "temperature: 0.3", "temperature: 0.1")},
                        "make the drafter more careful",
                        "--apply");
        assertThat(o.code()).as(o.out()).isZero();
        assertThat(o.out()).contains("Applied to").contains("main.loom");
        assertThat(Files.readString(p.resolve("main.loom")))
                .isEqualTo(before.replace("temperature: 0.3", "temperature: 0.1"));
    }

    @Test
    void aPatchThatDoesNotApplyOrBreaksTheScriptIsFedBackAndRepaired() throws Exception {
        Path p = project(false);
        String[] replies = {
            reply("main.loom", "not in the file at all", "x"),
            reply("main.loom", "to Drafter ->", "to Nobody ->"),
            reply("main.loom", "temperature: 0.3", "temperature: 0.2")
        };
        var model =
                new io.github.llm4j.loom.generate.ScriptedModel(n -> replies[Math.min(n, 2)], 150);
        var o =
                GenerateCommandTest.generate(
                        m -> model,
                        "tune it",
                        "--edit",
                        p.resolve("main.loom").toString(),
                        "--model",
                        "m");
        assertThat(o.code()).as(o.out() + o.err()).isZero();
        assertThat(model.asked.get(1)).contains("the text to replace is not there");
        assertThat(model.asked.get(2))
                .contains("Gate G1 (check) failed")
                .contains("Nobody")
                .contains("Reply with the whole patch again, against the original files");
        assertThat(o.out()).contains("temperature: 0.2");
    }

    @Test
    void aChangeThatBreaksAnExistingTestIsHeldBackUnlessItAlsoChangesTheTest() throws Exception {
        Path p = project(false);
        // sending now needs a second person: the test that expects the Notifier called once with
        // "yes" still holds,
        // but removing the Notifier's call breaks it
        String breaking =
                reply("main.loom", "alt (decision == \"yes\")", "alt (decision == \"never\")");
        var o =
                edit(
                        p,
                        new String[] {breaking},
                        "stop telling the customer",
                        "--max-attempts",
                        "1");
        assertThat(o.code()).isEqualTo(3);
        assertThat(o.out()).contains("✗ G3 tests").contains("approved reply is sent");
        var fixed =
                reply(
                        "tests/main.test.yaml",
                        "calls: { Drafter: 1, Notifier: 1 }",
                        "calls: { Drafter: 1 }");
        var o2 =
                edit(
                        p,
                        new String[] {breaking + fixed},
                        "stop telling the customer",
                        "--max-attempts",
                        "1");
        assertThat(o2.out())
                .contains("Tests changed (check they say what you now want): tests/main.test.yaml");
        assertThat(Files.readString(p.resolve("tests/main.test.yaml"))).contains("Notifier: 1");
    }

    @Test
    void aChangeThatMakesTheReviewWorseNeedsAcceptWorse() throws Exception {
        Path p = project(false);
        // a loop that can run a hundred times makes the worst case far more expensive
        String costly =
                reply(
                        "main.loom",
                        "tools: [Hook]  approve: [Hook] }",
                        "tools: [Hook]  approve: [Hook]  max_iterations: 200 }");
        var o =
                edit(
                        p,
                        new String[] {costly},
                        "polish the reply until it is right",
                        "--max-attempts",
                        "1");
        assertThat(o.out()).contains("✗ G2 review delta").contains("can now spend up to");
        assertThat(o.out()).contains("WORSE").contains("--accept-worse");
        assertThat(o.code()).isEqualTo(3);
        String before = Files.readString(p.resolve("main.loom"));
        assertThat(Files.readString(p.resolve("main.loom"))).isEqualTo(before);
        var accepted =
                edit(
                        p,
                        new String[] {costly},
                        "polish the reply until it is right",
                        "--max-attempts",
                        "1",
                        "--apply",
                        "--accept-worse");
        assertThat(accepted.code()).as(accepted.out()).isZero();
        assertThat(Files.readString(p.resolve("main.loom"))).contains("max_iterations: 200");
        // without --apply even an accepted change is only shown
    }

    @Test
    void thePlanFileIsKeptTrueOrTheChangeIsHeldBack() throws Exception {
        Path p = project(true);
        String addsAgent =
                reply(
                                "main.loom",
                                "agent Notifier {",
                                "agent Auditor { model: \"claude-haiku-5-5\"  prompt: \"auditor\" }\nagent Notifier {")
                        + "=== file: prompts/auditor.md ===\n---\ndescription: audits\n---\nYou audit.\n=== end ===\n";
        var held = edit(p, new String[] {addsAgent}, "add an auditor", "--max-attempts", "1");
        assertThat(held.out())
                .contains("✗ G5 plan")
                .contains("agent Auditor, which the plan does not list");
        assertThat(held.code()).isEqualTo(3);
        String plan = PlanJson.write(GenerateFixtures.plan());
        String edited =
                plan.replace(
                        "\"name\" : \"Notifier\"",
                        "\"name\" : \"Auditor\",\n    \"model\" : \"claude-haiku-5-5\",\n    \"role\" : \"audits\",\n    \"tools\" : [ ],\n    \"prompt\" : \"auditor\"\n  }, {\n    \"name\" : \"Notifier\"");
        var ok =
                edit(
                        p,
                        new String[] {
                            addsAgent
                                    + reply(
                                            "plan.json",
                                            "\"name\" : \"Notifier\"",
                                            "\"name\" : \"Auditor\",\n    \"model\" : \"claude-haiku-5-5\",\n    \"role\" : \"audits\",\n    \"tools\" : [ ],\n    \"prompt\" : \"auditor\"\n  }, {\n    \"name\" : \"Notifier\"")
                        },
                        "add an auditor",
                        "--max-attempts",
                        "1",
                        "--apply");
        assertThat(ok.code()).as(ok.out() + ok.err()).isZero();
        assertThat(Files.readString(p.resolve("PLAN.md"))).contains("**Auditor**");
        assertThat(edited).contains("Auditor");
    }

    @Test
    void anAmbiguousChangeGetsOneQuestionNotAGuessAndTheAnswerIsPassedOn() throws Exception {
        Path p = project(false);
        var asked =
                new io.github.llm4j.loom.generate.ScriptedModel(
                        n ->
                                n == 0
                                        ? "=== question ===\nApproved by the team lead or by the account owner?\n=== end ==="
                                        : reply(
                                                "main.loom",
                                                "temperature: 0.3",
                                                "temperature: 0.1"),
                        150);
        var o =
                GenerateCommandTest.generate(
                        m -> asked,
                        "add an approval",
                        "--edit",
                        p.resolve("main.loom").toString(),
                        "--model",
                        "m");
        assertThat(o.code()).isEqualTo(2);
        assertThat(o.out())
                .contains("nothing was guessed")
                .contains("Approved by the team lead or by the account owner?")
                .contains("--answer");
        assertThat(Files.readString(p.resolve("main.loom"))).contains("temperature: 0.3");
        var again =
                GenerateCommandTest.generate(
                        m -> asked,
                        "add an approval",
                        "--edit",
                        p.resolve("main.loom").toString(),
                        "--model",
                        "m",
                        "--answer",
                        "the team lead");
        assertThat(again.code()).as(again.out()).isZero();

        assertThat(asked.asked.get(1)).contains("Answer to your question: the team lead");
    }

    @Test
    void theModelCannotReachFilesOutsideTheProjectOrTheKeys() throws Exception {
        Path p = project(false);
        Files.writeString(p.resolve(".env"), "KEY=secret");
        String evil =
                "=== file: .env ===\nKEY=stolen\n=== end ===\n=== file: ../outside.loom ===\nx\n=== end ===\n";
        var o = edit(p, new String[] {evil}, "do it", "--max-attempts", "1", "--apply");
        assertThat(o.code()).isEqualTo(3);
        assertThat(Files.readString(p.resolve(".env"))).isEqualTo("KEY=secret");
        assertThat(Files.exists(dir.resolve("outside.loom"))).isFalse();
        assertThat(Files.exists(p.resolveSibling("outside.loom"))).isFalse();
    }

    @Test
    void aSavedPatchIsAppliedLaterWithoutTheModelAndOnlyToTheFilesItWasMadeFor() throws Exception {
        Path p = project(false);
        Path patch = dir.resolve("patch.json");
        var made =
                edit(
                        p,
                        new String[] {reply("main.loom", "temperature: 0.3", "temperature: 0.1")},
                        "calmer",
                        "--patch-out",
                        patch.toString());
        assertThat(made.code()).isZero();
        assertThat(made.out()).contains("Patch saved to");
        assertThat(Files.readString(p.resolve("main.loom"))).contains("temperature: 0.3");
        var applied =
                GenerateCommandTest.generate(
                        GenerateCommandTest.noModels(),
                        "--edit",
                        p.resolve("main.loom").toString(),
                        "--apply-patch",
                        patch.toString(),
                        "--apply");
        assertThat(applied.code()).as(applied.out() + applied.err()).isZero();
        assertThat(applied.out()).contains("✓ G1 check").contains("Applied to");
        assertThat(Files.readString(p.resolve("main.loom"))).contains("temperature: 0.1");
        // the project has moved on: the same patch no longer applies
        var stale =
                GenerateCommandTest.generate(
                        GenerateCommandTest.noModels(),
                        "--edit",
                        p.resolve("main.loom").toString(),
                        "--apply-patch",
                        patch.toString(),
                        "--apply");
        assertThat(stale.code()).isEqualTo(2);
        assertThat(stale.err()).contains("has changed since the patch was made");
    }

    @Test
    void theSpendCapStopsAnEditWithNothingChanged() throws Exception {
        Path p = project(false);
        var o =
                edit(
                        p,
                        new String[] {"I will think about it."},
                        "x",
                        "--max-tokens",
                        "100",
                        "--max-attempts",
                        "5");
        assertThat(o.code()).isEqualTo(4);
        assertThat(o.out()).contains("Stopped by the spend cap").contains("Nothing was changed");
    }

    @Test
    void aMavenProjectIsEditedThroughItsOwnLayout() throws Exception {
        Path p = Files.createDirectory(dir.resolve("mvn"));
        Map<String, String> files = new LinkedHashMap<>(GenerateFixtures.files());
        files.putAll(Writer.datasetStub(GenerateFixtures.plan()));
        try (var ws =
                Writer.render(
                        files,
                        GenerateFixtures.plan(),
                        Writer.Layout.MAVEN,
                        new Writer.Stamp("t", "d"),
                        dir)) {
            assertThat(ws.commit(p, false).ok()).isTrue();
        }
        var o =
                GenerateCommandTest.generate(
                        GenerateCommandTest.sequence(
                                reply("main.loom", "temperature: 0.3", "temperature: 0.1")),
                        "calmer",
                        "--edit",
                        p.resolve("src/main/resources/main.loom").toString(),
                        "--model",
                        "m",
                        "--apply");
        assertThat(o.code()).as(o.out() + o.err()).isZero();
        assertThat(Files.readString(p.resolve("src/main/resources/main.loom")))
                .contains("temperature: 0.1");
        assertThat(Files.exists(p.resolve("main.loom"))).isFalse();
    }

    @Test
    void usageErrorsExplain() throws Exception {
        Path p = project(false);
        assertThat(
                        GenerateCommandTest.generate(
                                        GenerateCommandTest.noModels(),
                                        "--edit",
                                        p.resolve("main.loom").toString())
                                .code())
                .isEqualTo(1);
        assertThat(
                        GenerateCommandTest.generate(
                                        GenerateCommandTest.noModels(),
                                        "x",
                                        "--edit",
                                        p.resolve("README.md").toString())
                                .err())
                .contains("not one");
        assertThat(PatchSet.parse("").isEmpty()).isTrue();
    }

    @Test
    void withAPriceFileTheCostChangeIsAlsoShownInMoney() throws Exception {
        Path p = project(false);
        Path prices =
                Files.writeString(dir.resolve("prices.txt"), "claude-haiku-5-5 = 1.00 / 5.00\n");
        var o =
                edit(
                        p,
                        new String[] {reply("main.loom", "temperature: 0.3", "temperature: 0.1")},
                        "make the drafter more careful",
                        "--prices",
                        prices.toString());
        assertThat(o.code()).as(o.out()).isZero();
        assertThat(o.out()).contains("cost of Main: typical").contains("(about $");
    }
}
