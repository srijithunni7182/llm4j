package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.model.Severity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R49 to R54 of spec 09: the one advisory line, and that it is never in the way. */
class ReviewNoticeTest {

    @TempDir Path dir;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    String tmpBefore;
    Map<String, String> environment = new java.util.HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        ReviewNotice.reset();
        ReviewNotice.defaultCapMillis = 60_000;
        tmpBefore = System.getProperty("java.io.tmpdir");
        System.setProperty(
                "java.io.tmpdir", Files.createDirectories(dir.resolve("tmp")).toString());
    }

    @AfterEach
    void restore() {
        System.setProperty("java.io.tmpdir", tmpBefore);
        ReviewNotice.defaultCapMillis = ReviewNotice.DEFAULT_CAP_MILLIS;
        ReviewNotice.reset();
    }

    WeaveEnv env() {
        return new WeaveEnv(
                m -> null,
                q -> "",
                new PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                new PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8),
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                environment::get);
    }

    File script(String text) throws Exception {
        Path f = dir.resolve("w.loom");
        Files.writeString(f, text);
        return f.toFile();
    }

    static final String SEARCH =
            "agent Searcher { model: \"m\"  system: \"You search.\"  tools: [web_search] }\n"
                    + "workflow Main(topic) {\n  delegate \"Find: {topic}\" to Searcher -> found\n  note \"{found}\"\n}\n";

    String notice(String text, String option) throws Exception {
        err.reset();
        ReviewNotice.reset();
        ReviewNotice.start(script(text), env(), option).await();
        return err.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void oneLineOnStandardErrorSaysWhatIsOpenAndThatItDoesNotBlock() throws Exception {
        String line = notice(SEARCH, null);
        assertThat(line)
                .startsWith("⚠ weave review: ")
                .contains(" open")
                .contains("— not blocking. Details: weave review")
                .contains("Silence: review.properties notice = off");
        assertThat(line.lines().count()).isEqualTo(1);
        assertThat(line).doesNotContain("\u001b");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void itAppearsOncePerProcess() throws Exception {
        ReviewNotice.reset();
        File s = script(SEARCH);
        ReviewNotice.start(s, env(), null).await();
        ReviewNotice.start(s, env(), null).await();
        assertThat(
                        err.toString(java.nio.charset.StandardCharsets.UTF_8)
                                .lines()
                                .filter(l -> l.startsWith("⚠"))
                                .count())
                .isEqualTo(1);
    }

    @Test
    void nothingIsPrintedWhenNothingIsOpenAtTheLevel() throws Exception {
        // only medium and lower findings exist here, so a high threshold says nothing
        assertThat(notice(SEARCH, "high")).doesNotContain("⚠");
    }

    @Test
    void theLevelDecidesWhichSeveritiesAreMentioned() throws Exception {
        assertThat(notice(SEARCH, "medium")).doesNotContain(" low");
        assertThat(notice(SEARCH, "low")).contains("⚠");
    }

    @Test
    void itCanBeTurnedOffByTheOptionTheEnvironmentOrTheSettingsFile() throws Exception {
        assertThat(notice(SEARCH, "off")).isEmpty();
        environment.put("LOOM_REVIEW_NOTICE", "off");
        assertThat(notice(SEARCH, null)).isEmpty();
        environment.clear();
        Files.writeString(dir.resolve("review.properties"), "notice = off\n");
        assertThat(notice(SEARCH, null)).isEmpty();
        // the option wins over the file
        assertThat(notice(SEARCH, "low")).contains("⚠");
    }

    @Test
    void acceptedAndBaselinedFindingsAreCountedApartNotAsOpen() throws Exception {
        String before = notice(SEARCH, null);
        assertThat(before).doesNotContain("accepted");
        Files.writeString(dir.resolve("review.properties"), "LR01.accept = project \"known\"\n");
        String after = notice(SEARCH, null);
        assertThat(after).contains("(1 accepted)");
    }

    @Test
    void aBrokenSettingsFileTurnsTheNoticeIntoOneUnavailableLineNeverAnError() throws Exception {
        Files.writeString(dir.resolve("review.properties"), "this is not a setting\n");
        String line = notice(SEARCH, null);
        assertThat(line).startsWith("review unavailable (");
        assertThat(line.lines().count()).isEqualTo(1);
    }

    @Test
    void aBadLevelIsReportedAsUnavailableToo() throws Exception {
        assertThat(notice(SEARCH, "loud"))
                .startsWith("review unavailable (")
                .contains("notice takes");
    }

    @Test
    void aReviewThatCannotBeReadIsUnavailableNotACrash() throws Exception {
        assertThat(notice("this is not a loom script {{{", null))
                .startsWith("review unavailable (");
    }

    @Test
    void aNoticeThatIsNotReadyInTimeIsDroppedNotWaitedFor() throws Exception {
        Files.writeString(dir.resolve("review.properties"), "notice.timeout = 0ms\n");
        assertThat(notice(SEARCH, null)).doesNotContain("⚠");
    }

    @Test
    void theSecondCommandOnTheSameScriptUsesTheCache() throws Exception {
        notice(SEARCH, null);
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "weave-review-notice");
        try (var files = Files.list(cache)) {
            assertThat(files.count()).isEqualTo(1);
        }
        // a changed script is a different key
        notice(SEARCH + "\n// edited\n", null);
        try (var files = Files.list(cache)) {
            assertThat(files.count()).isEqualTo(2);
        }
    }

    @Test
    void theLineIsFormattedFromTheCounts() {
        assertThat(ReviewNotice.format(new ReviewNotice.Counts(2, 3, 1, 4, 5, 2), Severity.LOW))
                .isEqualTo(
                        "⚠ weave review: 2 high · 3 medium · 1 low open (5 accepted, 2 baselined) — not blocking. Details: weave review   Silence: review.properties notice = off");
        assertThat(ReviewNotice.format(new ReviewNotice.Counts(0, 0, 0, 7, 0, 0), Severity.LOW))
                .isNull();
        assertThat(ReviewNotice.format(new ReviewNotice.Counts(0, 2, 5, 0, 0, 0), Severity.MEDIUM))
                .contains("2 medium open")
                .doesNotContain("low");
    }

    @Test
    void theStartingCommandsCarryTheOptionAndTheReviewCommandsDoNot() {
        var cli = WeaveCLI.commandLine();
        assertThat(cli.getSubcommands().get("run").getCommandSpec().findOption("--review-notice"))
                .isNotNull();
        assertThat(
                        cli.getSubcommands()
                                .get("package")
                                .getCommandSpec()
                                .findOption("--review-notice"))
                .isNotNull();
        assertThat(cli.getSubcommands().get("check").getCommandSpec().findOption("--review-notice"))
                .isNull();
    }
}
