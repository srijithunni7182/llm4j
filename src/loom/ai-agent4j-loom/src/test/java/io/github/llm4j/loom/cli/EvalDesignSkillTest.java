package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.eval.DatasetFolder;
import io.github.llm4j.loom.guide.Guide;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Phase 23: the evaluation-design skill is text, so what can go wrong is a claim that drifts from
 * the tool, a broken link, a sample that does not load, or a rule quietly dropped. A skill that
 * promises must be a skill that is true.
 */
class EvalDesignSkillTest {

    private static final Path REPO = Path.of("../../..");
    private static final Path SKILL = REPO.resolve(".claude/skills/llm4j-eval-design");
    private static final Path REFERENCES = SKILL.resolve("references");

    /** The nine rules, word for word: a later edit must not drop or soften one. */
    private static final List<String> RULES =
            List.of(
                    "Define what *correct* means before measuring quality. A property with no oracle is not tested.",
                    "Measure outcomes statistically; one run is not a result.",
                    "Model-level defences are mitigations (percentages); system-level controls are controls (100%). Never accept a percentage where a control is required; report which is which.",
                    "A mock run proves wiring, not quality; it is never presented as an evaluation.",
                    "A judge is an instrument: calibrated against humans before it gates, from a different model family than the agents, at temperature 0, one dimension per call, with its rubric in a versioned file.",
                    "Do not re-baseline to hide a regression; a new baseline needs a named approver and a reason.",
                    "Do not mistake consistency for correctness, or variability for failure.",
                    "Read a real answer under a cap before building a dataset around the workflow.",
                    "Never describe a report that has not been read.");

    /** The stages of the design, in the order the skill and the record both use. */
    private static final List<String> STAGES =
            List.of(
                    "Readiness",
                    "Layer map",
                    "Dimensions",
                    "Oracles",
                    "Dataset",
                    "Harness",
                    "Run protocol",
                    "Security",
                    "Regression and baselines",
                    "Gates",
                    "Ownership");

    @TempDir Path dir;

    private static String skill() throws Exception {
        return Files.readString(SKILL.resolve("SKILL.md"));
    }

    private static String reference(String name) throws Exception {
        return Files.readString(REFERENCES.resolve(name));
    }

    private static List<Path> everyFile() throws Exception {
        List<Path> files = new ArrayList<>();
        files.add(SKILL.resolve("SKILL.md"));
        try (Stream<Path> s = Files.list(REFERENCES)) {
            s.sorted().forEach(files::add);
        }
        return files;
    }

    private int guide(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GuideCommand c = new GuideCommand();
        new CommandLine(c).parseArgs(args);
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c2 ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        return GuideCommand.guide(c, env);
    }

    // ---- R1: the package

    @Test
    void theFrontMatterNamesTheFolderAndTriggersOnTheQuestionsADeveloperAsks() throws Exception {
        String text = skill();
        assertThat(text).startsWith("---\nname: llm4j-eval-design\ndescription: ");
        String description = text.substring(0, text.indexOf("\n---\n", 4));
        for (String trigger :
                List.of(
                        "test or evaluate an agent or workflow",
                        "how to know their agents are good",
                        "golden dataset should contain",
                        "trust an LLM judge",
                        "prompt injection",
                        "whether their evaluation is any good",
                        "what thresholds to gate on",
                        "how often to run it"))
            assertThat(description).as(trigger).contains(trigger);
    }

    @Test
    void everyReferenceTheSkillListsExistsAndEveryReferenceFileIsListed() throws Exception {
        List<String> listed = new ArrayList<>();
        Matcher m = Pattern.compile("`references/([a-z0-9-]+\\.md)`").matcher(skill());
        while (m.find()) if (!listed.contains(m.group(1))) listed.add(m.group(1));
        assertThat(listed).allSatisfy(f -> assertThat(REFERENCES.resolve(f)).exists());
        try (Stream<Path> s = Files.list(REFERENCES)) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList())
                    .isEqualTo(Guide.EVAL_REFERENCES.stream().sorted().toList());
        }
        assertThat(listed).containsAll(Guide.EVAL_REFERENCES);
    }

    @Test
    void noPageSendsTheReaderToARepositoryPathOrAnotherFolder() throws Exception {
        for (Path file : everyFile()) {
            // a web address may contain "docs/"; only a path inside the repository is a problem
            String text = Files.readString(file).replaceAll("https?://\\S+", "");
            assertThat(text).as(file.toString()).doesNotContain("docs/").doesNotContain("src/loom");
            assertThat(text).as(file.toString()).doesNotContain("](../").doesNotContain("](/");
        }
    }

    @Test
    void theSkillIsKeptShortEnoughToBeReadWhole() throws Exception {
        assertThat(skill().lines().count()).isLessThan(170);
    }

    // ---- R2: the stages and the record

    @Test
    void theSkillsStageTableAndTheRecordsSectionsAreTheSameEleven() throws Exception {
        String record = reference("design-record.md");
        String skill = skill();
        for (int i = 0; i < STAGES.size(); i++) {
            assertThat(skill).as("stage E" + i).contains("| E" + i + " | " + STAGES.get(i) + " |");
            assertThat(record).as("section " + i).contains("## " + i + ". " + STAGES.get(i));
        }
        assertThat(record).contains("## Decisions log").contains("## Gaps and workarounds").contains("## Open risks");
    }

    @Test
    void theChecklistIsShownAfterEveryStage() throws Exception {
        assertThat(skill())
                .contains("Show the checklist")
                .contains("after every stage")
                .contains("[ ] EVAL_DESIGN.md written and read back");
    }

    // ---- R3: ask, do not invent

    @Test
    void theSkillForbidsInventingGroundTruthAndMarksWhatItDrafts() throws Exception {
        assertThat(skill())
                .contains("You never write the answer key for domain facts")
                .contains("`author:assistant`")
                .contains("`reviewed_by:<name>`")
                .contains("**ask for examples**")
                .contains("`source:synthetic`");
        assertThat(reference("dataset-and-labelling.md"))
                .contains("**You do not write the ground truth.**")
                .contains("Ask the domain expert")
                .contains("`author:assistant`");
    }

    @Test
    void theWorkedExampleMarksWhatAnAssistantDraftedAndNamesWhatItWasNotGiven() throws Exception {
        String example = reference("design-record.md");
        assertThat(example)
                .contains("4 cases tagged `author:assistant`")
                .contains("**asked of the editor-in-chief; not yet provided**");
    }

    // ---- R5: the nine rules

    /** The rules a page lacks, numbered as the skill numbers them. */
    private static List<String> missingRules(String text) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < RULES.size(); i++)
            if (!text.contains((i + 1) + ". " + RULES.get(i))) missing.add("rule " + (i + 1));
        return missing;
    }

    @Test
    void theNineRulesArePresentWordForWord() throws Exception {
        assertThat(missingRules(skill())).isEmpty();
    }

    @Test
    void theCheckNoticesWhenAnyOneRuleIsRemovedFromACopy() throws Exception {
        String text = skill();
        for (int i = 0; i < RULES.size(); i++) {
            String without = text.replace((i + 1) + ". " + RULES.get(i), "");
            assertThat(missingRules(without)).as("rule " + (i + 1)).containsExactly("rule " + (i + 1));
        }
    }

    @Test
    void controlsAreSaidToBeMeasuredAtOneHundredPercentAndAPercentageIsNotAControl() throws Exception {
        assertThat(reference("security-testing.md"))
                .contains("System-level defences are controls, measured at 100%")
                .contains("A 99% injection-resistance rate means one crafted payload in a hundred succeeds");
    }

    @Test
    void theGuidesNumbersAreStatedAsStartingPointsTheExpertSets() throws Exception {
        assertThat(skill()).contains("starting points").contains("the domain expert sets them");
        assertThat(reference("quality-dimensions.md")).contains("starting points");
        assertThat(reference("dataset-and-labelling.md")).contains("starting points");
    }

    // ---- R6: reading results

    @Test
    void theFiveStepTestOfWhetherAMovementIsRealIsThere() throws Exception {
        String text = reference("judge-and-pipeline.md");
        for (String step :
                List.of(
                        "Outside the historical variance band?",
                        "Statistically significant?",
                        "Reproduces on a re-run at the same configuration?",
                        "Correlates with a configuration change in the window?",
                        "Reproduces and correlates with nothing?"))
            assertThat(text).contains(step);
        assertThat(text).contains("pass^N next to pass@1").contains("generalisation gap").contains("Know your resolution");
    }

    @Test
    void theResolutionTableAgreesWithTheArithmetic() throws Exception {
        // runs needed to detect a change of d points at 95% confidence and 80% power, for a pass
        // rate near one half, two samples of n runs: n = 2 * (1.96 + 0.84)^2 * 0.25 / d^2
        for (double[] row : new double[][] {{0.20, 100}, {0.10, 400}, {0.05, 1500}}) {
            double perSample = 2 * Math.pow(1.96 + 0.84, 2) * 0.25 / (row[0] * row[0]);
            double total = perSample * 2;
            assertThat(total / row[1]).as("d=" + row[0]).isBetween(0.5, 3.0);
        }
        assertThat(reference("judge-and-pipeline.md"))
                .contains("20 percentage points needs about 100 runs")
                .contains("10 points about 400")
                .contains("5 points about 1,500");
    }

    // ---- R7: review mode

    @Test
    void reviewModeListsEveryAntiPatternWithTheEvidenceThatSettlesIt() throws Exception {
        String failures = reference("failure-modes.md");
        for (String pattern :
                List.of(
                        "same model family",
                        "Only cases the system currently passes",
                        "No unanswerable cases",
                        "without domain review",
                        "no metadata",
                        "owned by whoever owns the prompts",
                        "Never refreshed",
                        "unvalidated judge that gates",
                        "Thresholds set at current performance",
                        "mock result quoted as quality"))
            assertThat(failures.toLowerCase()).as(pattern).contains(pattern.toLowerCase());
        assertThat(failures).contains("Evidence to look for").contains("**not verified**");
        assertThat(skill()).contains("EVAL_REVIEW.md").contains("\"not verified\", never assumed");
    }

    // ---- R8: hand-overs

    @Test
    void theSkillHandsOverToTheOtherThreeAndTheyPointBack() throws Exception {
        assertThat(skill())
                .contains("`llm4j-workflow-guide`")
                .contains("`llm4j-harness-design`")
                .contains("`llm4j-loom-deployer`");
        for (String other :
                List.of("llm4j-workflow-guide", "llm4j-harness-design", "llm4j-loom-deployer"))
            assertThat(Files.readString(REPO.resolve(".claude/skills/" + other + "/SKILL.md")))
                    .as(other)
                    .contains("llm4j-eval-design");
    }

    // ---- R4: the mapping names only what exists

    @Test
    void theMappingNamesOnlyWeaveCommandsAndOptionsThatExist() throws Exception {
        CommandLine root = WeaveCLI.commandLine();
        List<String> missing = new ArrayList<>();
        for (Path file : everyFile()) {
            Matcher m =
                    Pattern.compile("`weave ([a-z][a-z-]*)((?: [^`]*)?)`")
                            .matcher(Files.readString(file));
            while (m.find()) {
                CommandLine command = root.getSubcommands().get(m.group(1));
                if (command == null) {
                    missing.add(file.getFileName() + ": weave " + m.group(1));
                    continue;
                }
                for (String word : m.group(2).split(" "))
                    if (word.startsWith("--") && !word.contains("<"))
                        if (!command.getCommandSpec().optionsMap().containsKey(word.replaceAll("[,.;:)]+$", "")))
                            missing.add(file.getFileName() + ": weave " + m.group(1) + " " + word);
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void everyGapRowNamesARegisterIdThatExistsAndTheRecordAskedFor() throws Exception {
        String register = Files.readString(REPO.resolve("docs/specs/FIELD_FINDINGS.md"));
        String mapping = reference("eval4j-loom-mapping.md");
        Matcher m = Pattern.compile("\\*\\*(F\\d{2})\\*\\*").matcher(mapping);
        int found = 0;
        while (m.find()) {
            found++;
            assertThat(register).as(m.group(1)).contains("| " + m.group(1) + " |");
        }
        assertThat(found).isGreaterThanOrEqualTo(6);
    }

    @Test
    void everyRowOfTheMappingEitherSaysNoneOrNamesAGapAndAWorkaround() throws Exception {
        for (String line : reference("eval4j-loom-mapping.md").split("\n")) {
            if (!line.startsWith("| **E")) continue;
            String[] cells = line.split("\\|");
            String gap = cells[cells.length - 1].isBlank() ? cells[cells.length - 2] : cells[cells.length - 1];
            assertThat(gap.strip()).as(line).isNotEmpty();
            boolean none = gap.strip().toLowerCase().startsWith("none");
            boolean named = gap.contains("**F") || gap.toLowerCase().contains("workaround");
            boolean said = gap.contains("not yet") || gap.contains("no ") || gap.contains("a decision");
            assertThat(none || named || said).as(line).isTrue();
        }
    }

    // ---- R9: it installs

    @Test
    void installingOnlyTheEvalSkillWritesItsFilesVerbatimAndNothingElse() throws Exception {
        assertThat(guide("--install-skill", dir.toString(), "--skill", "eval")).isZero();
        Path folder = Guide.evalFolder(dir);
        assertThat(Files.readString(folder.resolve("SKILL.md")))
                .isEqualTo(skill())
                .startsWith("---\nname: llm4j-eval-design\n");
        for (String ref : Guide.EVAL_REFERENCES)
            assertThat(Files.readString(folder.resolve("references").resolve(ref)))
                    .as(ref)
                    .isEqualTo(reference(ref));
        assertThat(Guide.harnessFolder(dir)).doesNotExist();
        assertThat(Guide.skillFolder(dir)).doesNotExist();
        assertThat(Guide.deployerFolder(dir)).doesNotExist();
    }

    @Test
    void aSecondInstallWithoutForceSaysSoAndForceReplaces() throws Exception {
        assertThat(guide("--install-skill", dir.toString(), "--skill", "eval")).isZero();
        assertThat(guide("--install-skill", dir.toString(), "--skill", "eval")).isEqualTo(2);
        assertThat(guide("--install-skill", dir.toString(), "--skill", "eval", "--force")).isZero();
    }

    // ---- R10: attribution

    @Test
    void everyPageThatDistilsTheGuideSaysSoAndTheExternalWorksAreLinked() throws Exception {
        for (Path file : everyFile()) {
            String name = file.getFileName().toString();
            if (name.equals("conventions.md")) continue;
            assertThat(Files.readString(file)).as(name).contains("Quality Assurance for AI agentic systems");
        }
        assertThat(reference("principles.md"))
                .contains("https://hamel.dev/blog/posts/evals/")
                .contains("https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/")
                .contains("https://genai.owasp.org/resource/owasp-genai-llm-top-10-2026/");
        assertThat(reference("judge-and-pipeline.md"))
                .contains("https://arxiv.org/abs/2306.05685")
                .contains("https://hamel.dev/notes/llm/evals/");
        assertThat(reference("security-testing.md"))
                .contains("https://arxiv.org/abs/2506.08837")
                .contains("https://www.promptfoo.dev/docs/red-team/");
    }

    // ---- safety

    @Test
    void noPageAsksForAKeyOrPutsOneInAnExampleFile() throws Exception {
        for (Path file : everyFile()) {
            String text = Files.readString(file);
            assertThat(text).as(file.toString()).doesNotContainPattern("sk-[A-Za-z0-9]{20,}");
            assertThat(text).as(file.toString()).doesNotContainPattern("AIza[0-9A-Za-z_-]{30,}");
        }
        assertThat(reference("security-testing.md"))
                .contains("sandboxed or fake tools")
                .contains("never against a real sender, payer or writer");
    }

    // ---- samples

    private static List<String> blocks(String text, String language) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("```" + language + "\\n(.*?)```", Pattern.DOTALL).matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void theDatasetCaseInTheReferenceLoadsWithTheRealLoader() throws Exception {
        List<String> yaml = blocks(reference("dataset-and-labelling.md"), "yaml");
        assertThat(yaml).hasSize(1);
        Path project = Files.createDirectories(dir.resolve("project"));
        Path script = project.resolve("main.loom");
        Files.writeString(
                script,
                "agent Replier { model: \"gemini-2.5-flash\" system: \"Answer.\" }\n"
                        + "tool LookupOrder { use: duckduckgo }\n"
                        + "workflow Main(message) {\n"
                        + "    delegate \"{message}\" to Replier -> reply\n"
                        + "    return reply\n"
                        + "}\n");
        Path golden = Files.createDirectories(project.resolve("eval/golden"));
        Files.writeString(golden.resolve("workflow.yaml"), yaml.get(0));
        LoomScript loaded = new LoomParser(new Lexer(Files.readString(script)).tokenize()).parseScript();

        DatasetFolder.Plan plan = DatasetFolder.plan(loaded, golden, null);

        assertThat(plan.problems()).isEmpty();
        assertThat(plan.scenarioCount()).isEqualTo(1);
    }

    @Test
    void theWorkedExampleHasAllElevenSectionsAReasonForEveryDimensionAndAnOwnerForEveryRole()
            throws Exception {
        String record = reference("design-record.md");
        String example = blocks(record, "markdown").get(1);
        for (int i = 0; i < STAGES.size(); i++)
            assertThat(example).as("section " + i).contains("## " + i + ". " + STAGES.get(i));
        for (String dimension :
                List.of(
                        "Relevance",
                        "Groundedness",
                        "Correctness",
                        "Completeness",
                        "Calibration",
                        "Instruction following",
                        "Coherence",
                        "Safety",
                        "Efficiency")) {
            Matcher row = Pattern.compile("\\| " + dimension + " \\| (yes|no) \\| (.+) \\|").matcher(example);
            assertThat(row.find()).as(dimension).isTrue();
            assertThat(row.group(2).strip()).as(dimension + " has a reason").isNotEmpty();
        }
        for (String role :
                List.of(
                        "Dataset owner",
                        "Judge owner",
                        "Gate approver",
                        "Owner of the prohibited-operations list")) {
            Matcher row = Pattern.compile("\\| " + role + "[^|]* \\| (.+) \\|").matcher(example);
            assertThat(row.find()).as(role).isTrue();
            assertThat(row.group(1).strip()).as(role + " is named").isNotEmpty();
        }
    }

    @Test
    void theTemplateAndTheExampleHaveTheSameHeadings() throws Exception {
        List<String> blocks = blocks(reference("design-record.md"), "markdown");
        assertThat(blocks).hasSize(2);
        assertThat(headings(blocks.get(1)))
                .isEqualTo(headings(blocks.get(0)));
    }

    private static List<String> headings(String markdown) {
        List<String> out = new ArrayList<>();
        for (String line : markdown.split("\n"))
            if (line.startsWith("## ")) out.add(line.replaceAll("^## ", ""));
        return out;
    }
}
