package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R36 to R39 of spec 05-fabric-ui (5f-3a): the guide says what changed (context and compaction, tasks that fail, the trifecta, watching a run), and its Loom blocks really check. */
class GuideChapter14Test {

    static final Path GUIDE = Path.of("../../../docs/guide");

    private static String chapter(String file) throws Exception {
        return Files.readString(GUIDE.resolve(file));
    }

    @Test
    void chapter14IsListedAndTravelsInsideTheJar() throws Exception {
        assertThat(Guide.find("14")).isPresent();
        assertThat(Guide.find("context-and-compaction")).isPresent();
        assertThat(Guide.pages()).anyMatch(p -> p.name().equals("14") && p.title().contains("Context and compaction"));
        assertThat(Guide.read("guide/14-context-and-compaction.md")).contains(chapter("14-context-and-compaction.md"));
        assertThat(Guide.pages().stream().filter(p -> p.name().equals("6")).count()).as("each chapter is listed once").isEqualTo(1);
    }

    @Test
    void chapter14TeachesWhenToUseItHowToDeclareItAndHowToTellItWorks() throws Exception {
        String c = chapter("14-context-and-compaction.md");
        for (String must : List.of("context {", "compact:", "trigger:", "target:", "keep_recent", "max_compactions", "trim_tool_results", "summarize", "drop_oldest", "chain(",
                ".windows", ".loct", "weave check", "weave audit", "context_nearly_full", "compaction_thrash", "truncated_answers", "ContextCompactor", "Gate",
                "llm4j-loom-deployer", "--observe")) {
            assertThat(c).as("chapter 14 mentions " + must).contains(must);
        }
        assertThat(c).as("it says when NOT to bother").containsIgnoringCase("short run");
    }

    @Test
    void theOtherChaptersSayWhatChanged() throws Exception {
        String build = chapter("06-build-the-workflow.md");
        assertThat(build).contains("context {").contains("retry").contains("on_failure").contains("chapter 14").contains("failed_name");
        String audit = chapter("07-validate-and-audit.md");
        assertThat(audit).contains("trifecta: accepted").contains("reach").contains("weave audit").contains(".inspect").containsIgnoringCase("tools declare");
        String live = chapter("09-go-live.md");
        assertThat(live).contains("llm4j-loom-deployer").contains("--observe").contains("Fabric").contains("windows");
        String best = chapter("10-best-practices.md");
        assertThat(best).contains("context window").contains("trifecta").contains("--observe");
        String map = chapter("README.md");
        assertThat(map).contains("14-context-and-compaction.md").contains("llm4j-loom-deployer");
        String java = chapter("12-java-quick-reference.md");
        assertThat(java).contains("setContextWindows").contains("registerCompactor").contains("Observe.open").contains("stats()");
    }

    private static final Pattern CHECKED = Pattern.compile("<!-- weave check -->\\s*```loom\\n(.*?)```", Pattern.DOTALL);

    private static int check(PrintStream capture, String... args) {
        PrintStream old = System.out;
        System.setOut(capture);
        try {
            return WeaveCLI.commandLine().execute(args);
        } finally {
            System.setOut(old);
        }
    }

    @Test
    void everyMarkedLoomBlockInTheChaptersPassesWeaveCheck(@TempDir Path dir) throws Exception {
        List<String> failures = new ArrayList<>();
        int seen = 0;
        for (String file : List.of("06-build-the-workflow.md", "07-validate-and-audit.md", "09-go-live.md", "14-context-and-compaction.md")) {
            Matcher m = CHECKED.matcher(chapter(file));
            while (m.find()) {
                seen++;
                Path script = dir.resolve("block" + seen + ".loom");
                Files.writeString(script, m.group(1));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int code = check(new PrintStream(out, true), "check", script.toString(), "--no-env");
                if (code != 0) failures.add(file + " block " + seen + ":\n" + out);
            }
        }
        assertThat(seen).as("the chapters carry checked blocks").isGreaterThanOrEqualTo(3);
        assertThat(failures).isEmpty();
    }
}
