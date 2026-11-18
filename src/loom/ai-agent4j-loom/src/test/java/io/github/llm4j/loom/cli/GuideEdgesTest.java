package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 37: the Guide's edges. Messages that are too large or need no answer, arguments that are
 * missing, checkpoints of every kind, the evaluation summary with a report, damaged events, learner
 * and skip files, a full budget and long tool lists.
 */
class GuideEdgesTest {
    @TempDir Path dir;
    @TempDir Path home;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
    private Path project;

    @BeforeEach
    void setUp() {
        project = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", project.toString(), "--flat")).isZero();
    }

    private static WeaveEnv quiet() {
        PrintStream sink = new PrintStream(new java.io.ByteArrayOutputStream());
        return WeaveEnv.system().withStreams(sink, sink).withEnvLookup(k -> null);
    }

    private GuideMcpServer server(Path learnerHome) {
        return new GuideMcpServer(project.resolve("main.loom"), quiet(), new GuideEvents(project, CLOCK), new GuideLearner(learnerHome, project, CLOCK), (a, args) -> "shown");
    }

    private static JsonNode call(GuideMcpServer s, String tool, Map<String, Object> args) throws Exception {
        String msg = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call", "params", Map.of("name", tool, "arguments", args)));
        return s.handle(msg).path("result");
    }

    private static String text(JsonNode r) {
        return r.path("content").get(0).path("text").asText();
    }

    @Test
    void pingAndTheModernDiscoveryAreAnsweredAndAnOversizedMessageIsRefused() throws Exception {
        GuideMcpServer s = server(home);
        assertThat(s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").has("result")).isTrue();
        assertThat(s.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"server/discover\",\"params\":{}}").path("result").path("serverInfo").path("name").asText()).isEqualTo("llm4j-guide");
        assertThat(s.handle("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}")).isNull();

        StringWriter out = new StringWriter();
        s.serve(new BufferedReader(new StringReader("{\"x\":\"" + "a".repeat(1_000_001) + "\"}\n")), out);
        assertThat(JSON.readTree(out.toString().strip()).path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void aMissingArgumentIsSaidAndNothingRuns() throws Exception {
        JsonNode r = call(server(home), "explain_finding", Map.of());
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).isEqualTo("Missing argument: rule");
    }

    @Test
    void aRealRuleIsExplained() throws Exception {
        String t = text(call(server(home), "explain_finding", Map.of("rule", "LA02")));
        assertThat(t).isNotBlank().doesNotStartWith("No explanation");
    }

    @Test
    void checkpointsOfEveryKind() throws Exception {
        GuideMcpServer s = server(home);
        assertThat(call(s, "journey_checkpoint", Map.of("stage", "deploy", "did", "finished", "why", "x")).path("isError").asBoolean()).isTrue();
        assertThat(text(call(s, "journey_checkpoint", Map.of("stage", "deploy", "did", "started", "why", "writing files")))).isEqualTo("Recorded.");
        assertThat(text(call(s, "journey_checkpoint", Map.of("stage", "deploy", "did", "skipped", "why", "later")))).contains("weave next --skip deploy");
        assertThat(WeaveCLI.commandLine().execute("next", project.toString(), "--skip", "deploy", "--because", "later")).isZero();
        assertThat(text(call(s, "journey_checkpoint", Map.of("stage", "deploy", "did", "skipped", "why", "later")))).isEqualTo("Recorded.");
    }

    @Test
    void theEvaluationSummaryNamesTheLastEvaluationAndItsReport() throws Exception {
        EvalRecord.write(project, "real", "version 1", "abc");
        Files.createDirectories(project.resolve("eval-runs/report"));
        Files.writeString(project.resolve("eval-runs/report/index.html"), "<html></html>");

        String t = text(call(server(home), "eval_summary", Map.of()));

        assertThat(t).contains("Last evaluation: real").contains("eval-runs/report/index.html");
    }

    @Test
    void aFileThatDoesNotExistYetInsideTheProjectIsStillInside() throws Exception {
        assertThat(server(home).safePath("notes/later.md").toString()).startsWith(project.toString());
    }

    @Test
    void aLearnerRecordThatCannotBeWrittenIsAnErrorTheGuideSees() throws Exception {
        Path notAFolder = Files.writeString(dir.resolve("home-is-a-file"), "x");
        JsonNode r = call(server(notAFolder), "record_learning", Map.of("concept", "judge", "level", "seen"));
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).startsWith("Could not do it");
        assertThat(call(server(home), "record_learning", Map.of("concept", "judge", "level", "mastered")).path("isError").asBoolean()).isTrue();
    }

    @Test
    void aDamagedLearnerRecordReadsAsEmptyAndCanBeReset() throws Exception {
        GuideLearner l = new GuideLearner(home, project, CLOCK);
        Files.createDirectories(home.resolve(".loom/guide"));
        Files.writeString(home.resolve(".loom/guide/learner.json"), "{ broken");
        assertThat(l.both().path("person").isMissingNode() || l.both().path("person").isEmpty()).isTrue();
        l.record("judge", "seen");
        l.reset();
        assertThat(Files.exists(home.resolve(".loom/guide/learner.json"))).isFalse();
    }

    @Test
    void damagedEventLinesAndADamagedDiscussedMarkAreSkipped() throws Exception {
        GuideEvents e = new GuideEvents(project, CLOCK);
        e.append("saved", null, null, null);
        Files.writeString(e.file(), Files.readString(e.file()) + "{ broken\n\n");
        Files.writeString(e.file().resolveSibling("discussed.json"), "nope");

        assertThat(e.undiscussed(false).events()).hasSize(1);
        assertThat(e.undiscussed(false).events().get(0).has("stage")).isFalse();
        assertThat(e.undiscussedCount()).isEqualTo(1);
        e.undiscussed(true);
        assertThat(e.undiscussedCount()).isZero();
    }

    @Test
    void aSkipListEntryWithNoReasonMakesTheFileUnreadableAndNothingCanBeRecordedOverIt() throws Exception {
        Files.createDirectories(project.resolve(".loom"));
        Files.writeString(SkippedStages.file(project), "{\"skips\":[{\"stage\":\"deploy\",\"because\":\"\"}]}");

        SkippedStages.Read r = SkippedStages.read(project);
        assertThat(r.skips()).isEmpty();
        assertThat(r.warning()).get().asString().contains("no stage or no reason");
        assertThatThrownBy(() -> SkippedStages.skip(project, "deploy", "x", "me", CLOCK.instant())).isInstanceOf(IOException.class);
        assertThat(WeaveCLI.commandLine().execute("next", project.toString(), "--skip", "deploy", "--because", "x")).isEqualTo(1);
    }

    @Test
    void unskippingAStageThatWasNotSkippedSaysSo() throws Exception {
        assertThat(SkippedStages.unskip(project, "deploy")).isFalse();
    }

    @Test
    void anUnknownStageInTheSkipFileIsListedWithNoLossText() throws Exception {
        Files.createDirectories(project.resolve(".loom"));
        Files.writeString(SkippedStages.file(project), "{\"skips\":[{\"stage\":\"old-stage\",\"because\":\"renamed\"}]}");

        Map<String, Object> m = GuideContext.of(project.resolve("main.loom"), quiet());

        assertThat(m.get("skips").toString()).contains("old-stage").contains("missing=");
    }

    @Test
    void aFullBudgetAndLongToolListsAreSummarisedWithinBounds() throws Exception {
        Path script = project.resolve("main.loom");
        String text = Files.readString(script).replaceFirst("budget \\{[^}]*\\}",
                java.util.regex.Matcher.quoteReplacement("budget { tokens: 90000  calls: 20  searches: 4  cost: \"$1.50\" }"));
        StringBuilder more = new StringBuilder(text).append('\n');
        for (int i = 0; i < GuideContext.MAX_LISTED + 5; i++)
            more.append("tool Hook").append(i).append(" { use: webhook  url: env.HOOK_URL  format: slack  effect: changes }\n");
        Files.writeString(script, more.toString());

        Map<String, Object> m = GuideContext.of(script, quiet());

        @SuppressWarnings("unchecked")
        Map<String, Object> budget = (Map<String, Object>) m.get("budget");
        assertThat(budget).containsKeys("tokens", "calls", "searches", "cost");
        assertThat(budget.get("cost")).isEqualTo("1.50");
        java.util.List<?> tools = (java.util.List<?>) m.get("tools");
        assertThat(tools).hasSize(GuideContext.MAX_LISTED + 1);
        assertThat(tools.get(GuideContext.MAX_LISTED).toString()).contains("more=5");
        assertThat(GuideContext.words(GuideContextCommand.json(m))).isLessThanOrEqualTo(GuideContext.MAX_WORDS);
        assertThat(GuideContext.words("   ")).isZero();
    }

    @Test
    void aStaleRealEvaluationTriggersItsMoment() throws Exception {
        EvalRecord.write(project, "real", io.github.llm4j.loom.eval.DatasetIdentity.of(project.resolve("eval/golden")).describe(), "an-older-script");

        Map<String, Object> m = GuideContext.of(project.resolve("main.loom"), quiet());

        assertThat(m.get("triggers").toString()).contains("eval-stale");
        Map<String, Object> seen = new HashMap<>();
        for (Object mo : (java.util.List<?>) m.get("moments")) seen.put(String.valueOf(((Map<?, ?>) mo).get("id")), mo);
        assertThat(seen).containsKey("eval-stale");
    }
}
