package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 37, the review of PR 131: each finding pinned by a test. Unskipping undoes a README skip, the two skip flags are
 * not taken together, a stray exception is an error and not the end of the server, a slow call does not hold up a ping,
 * a crashed editor is not an open one, events come oldest first with what is left counted, the events file survives a
 * torn line, and the summary stays under its word limit whatever a hand-edited skip file holds.
 */
class GuideReviewFixesTest {
    @TempDir Path dir;
    @TempDir Path home;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC);
    private Path project;

    @BeforeEach
    void setUp() {
        project = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", project.toString(), "--flat")).isZero();
    }

    private record Ran(int code, String out, String err) {}

    private static Ran weave(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new Ran(WeaveCLI.commandLine().execute(args), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private String state(String stage) throws Exception {
        JsonNode j = JSON.readTree(weave("next", project.toString(), "--format", "json").out());
        for (JsonNode s : j.path("stages")) if (s.path("id").asText().equals(stage)) return s.path("state").asText();
        throw new AssertionError(stage);
    }

    private static WeaveEnv quiet() {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        return WeaveEnv.system().withStreams(sink, sink).withEnvLookup(k -> null);
    }

    private GuideMcpServer server(EditorChannel editor) {
        return new GuideMcpServer(project.resolve("main.loom"), quiet(), new GuideEvents(project, CLOCK), new GuideLearner(home, project, CLOCK), editor);
    }

    @Test
    void unskippingOneEvaluationStageUndoesTheReadmeSkipAndKeepsTheOtherTwo() throws Exception {
        for (String s : GuideJourneyStages.EVALUATION)
            assertThat(weave("next", project.toString(), "--skip", s, "--because", "a demo").code()).isZero();
        assertThat(Files.readString(project.resolve("README.md"))).contains("Evaluation: skipped");

        Ran r = weave("next", project.toString(), "--unskip", "design-evaluation");

        assertThat(r.out()).contains("No longer skipped: design-evaluation");
        assertThat(Files.readString(project.resolve("README.md"))).doesNotContain("Evaluation: skipped");
        assertThat(state("design-evaluation")).isEqualTo("not-started");
        assertThat(state("real-evaluation")).isEqualTo("skipped");
    }

    @Test
    void aReadmeSkipWrittenByHandIsUndoneTheSameWay() throws Exception {
        Files.writeString(project.resolve("README.md"), "# Demo\n\nEvaluation: skipped (a demo for Friday)\n");
        assertThat(state("golden-dataset")).isIn("skipped", "done");
        assertThat(state("design-evaluation")).isEqualTo("skipped");

        assertThat(weave("next", project.toString(), "--unskip", "design-evaluation").out()).contains("No longer skipped");

        assertThat(state("design-evaluation")).isEqualTo("not-started");
        assertThat(state("real-evaluation")).isEqualTo("skipped");
        assertThat(JSON.readTree(SkippedStages.file(project).toFile()).toString()).contains("README said: Evaluation: skipped (a demo for Friday)");
    }

    @Test
    void skipAndUnskipTogetherAreRefused() {
        Ran r = weave("next", project.toString(), "--skip", "deploy", "--because", "x", "--unskip", "preflight");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("not both");
        assertThat(Files.exists(SkippedStages.file(project))).isFalse();
    }

    @Test
    void aStrayExceptionInAToolIsAnErrorAndTheServerKeepsServing() throws Exception {
        GuideMcpServer s = server((a, args) -> {
            throw new IllegalStateException("the editor exploded");
        });
        JsonNode r = s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"show_graph\",\"arguments\":{}}}").path("result");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(r.path("content").get(0).path("text").asText()).contains("the editor exploded");
        assertThat(s.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}").has("result")).isTrue();
    }

    @Test
    void aBatchRequestGetsAnErrorNotSilence() {
        JsonNode r = server((a, args) -> "x").handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(r.path("error").path("message").asText()).contains("one message per line");
    }

    @Test
    void aCallThatWaitsForTheEditorDoesNotHoldUpAPing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        GuideMcpServer s = server((a, args) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "shown";
        });
        PipedWriter toServer = new PipedWriter();
        PipedReader fromClient = new PipedReader(toServer);
        StringWriter out = new StringWriter();
        Thread serving = new Thread(() -> {
            try {
                s.serve(new BufferedReader(fromClient), out);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        serving.start();
        toServer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"show_graph\",\"arguments\":{}}}\n");
        toServer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n");
        toServer.flush();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!out.toString().contains("\"id\":2") && System.nanoTime() < until) Thread.onSpinWait();

        assertThat(out.toString()).as("the ping is answered while the call waits").contains("\"id\":2").doesNotContain("\"id\":1");
        release.countDown();
        toServer.close();
        serving.join(10_000);
        assertThat(out.toString()).contains("\"id\":1");
    }

    @Test
    void anEditorFileLeftByACrashedEditorIsNotAnOpenEditor() throws Exception {
        Path guide = Files.createDirectories(project.resolve(".loom/guide"));
        Files.writeString(guide.resolve("editor.json"), "{\"pid\":424242}");
        EditorChannel dead = new EditorChannel.Mailbox(project, CLOCK, d -> {}, pid -> false);
        assertThat(dead.send("show_graph", JSON.createObjectNode())).isEqualTo(EditorChannel.NOT_OPEN);
        Files.writeString(guide.resolve("editor.json"), "{\"at\":\"2026\"}");
        assertThat(new EditorChannel.Mailbox(project, CLOCK, d -> {}, pid -> true).send("show_graph", JSON.createObjectNode())).isEqualTo(EditorChannel.NOT_OPEN);
        assertThat(guide.resolve("requests")).doesNotExist();
    }

    @Test
    void anAnswerNoOneCollectedIsSweptAway() throws Exception {
        Path guide = Files.createDirectories(project.resolve(".loom/guide"));
        Files.writeString(guide.resolve("editor.json"), "{\"pid\":1}");
        Path old = Files.writeString(Files.createDirectories(guide.resolve("answers")).resolve("aaaaaaaaaaaaaaaaaaaaaaaa.json"), "{\"result\":\"late\"}");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(CLOCK.instant().minus(java.time.Duration.ofHours(1))));
        Instant[] now = {CLOCK.instant()};
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        new EditorChannel.Mailbox(project, moving, d -> now[0] = now[0].plus(d), pid -> true).send("show_graph", JSON.createObjectNode());
        assertThat(old).doesNotExist();
    }

    @Test
    void eventsComeOldestFirstAndWhatIsLeftIsCounted() throws Exception {
        Instant[] now = {Instant.parse("2026-11-18T00:00:00Z")};
        Clock ticking = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0] = now[0].plusSeconds(1); }
        };
        GuideEvents e = new GuideEvents(project, ticking);
        e.append("claim-mismatch", "deploy", "done", Map.of());
        for (int i = 0; i < GuideEvents.MAX_PER_READ + 9; i++) e.append("saved", "", "main.loom", Map.of());

        GuideEvents.Batch first = e.undiscussed(true);
        assertThat(first.events()).hasSize(GuideEvents.MAX_PER_READ);
        assertThat(first.events().get(0).path("kind").asText()).as("the oldest is not dropped").isEqualTo("claim-mismatch");
        assertThat(first.more()).isEqualTo(10);
        GuideEvents.Batch second = e.undiscussed(true);
        assertThat(second.events()).hasSize(10);
        assertThat(second.more()).isZero();
        assertThat(e.undiscussed(true).events()).isEmpty();
    }

    @Test
    void theServersFileSurvivesATornLineAndReadsTheEditorsToo() throws Exception {
        GuideEvents e = new GuideEvents(project, CLOCK);
        e.append("saved", "", "a", Map.of());
        Files.writeString(e.file(), Files.readString(e.file()) + "{\"seq\":2,\"ki");
        assertThat(e.append("checkpoint", "deploy", "done", Map.of())).as("numbers carry on after a torn line").isEqualTo(2);
        Files.writeString(e.source(GuideEvents.EDITOR), "{\"seq\":1,\"at\":\"2026-11-18T09:00:00Z\",\"kind\":\"stage-changed\"}\n");

        List<String> kinds = e.undiscussed(false).events().stream().map(n -> n.path("source").asText() + ":" + n.path("kind").asText()).toList();

        assertThat(kinds).containsExactly("editor:stage-changed", "server:saved", "server:checkpoint");
    }

    @Test
    void theSummaryStaysUnderItsLimitWhateverAHandEditedSkipFileHolds() throws Exception {
        StringBuilder skips = new StringBuilder("{\"skips\":[");
        String reason = "because ".repeat(2000);
        for (int i = 0; i < JourneyStages.IDS.size(); i++)
            skips.append(i == 0 ? "" : ",").append("{\"stage\":\"").append(JourneyStages.IDS.get(i)).append("\",\"because\":\"").append(reason).append("\"}");
        Files.createDirectories(project.resolve(".loom"));
        Files.writeString(SkippedStages.file(project), skips.append("]}").toString());

        Map<String, Object> m = GuideContext.of(project.resolve("main.loom"), quiet());
        String json = GuideContextCommand.json(m);

        assertThat(GuideContext.words(json)).isLessThanOrEqualTo(GuideContext.MAX_WORDS);
        assertThat(json).doesNotContain(reason.substring(0, SkippedStages.MAX_TEXT + 20));
    }

    @Test
    void anOversizedSummaryIsCutInStepsAndSaysWhatWasLeftOut() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(GuideContext.of(project.resolve("main.loom"), quiet()));
        List<Object> warnings = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) warnings.add("warning ".repeat(200));
        m.put("warnings", warnings);

        Map<String, Object> cut = GuideContext.bounded(m);

        assertThat(GuideContext.words(GuideContextCommand.json(cut))).isLessThanOrEqualTo(GuideContext.MAX_WORDS);
        assertThat(String.valueOf(cut.get("cut"))).contains("To stay under").contains("more warnings");
    }

    @Test
    void anOrdinarySummaryIsNotCut() {
        assertThat(GuideContext.of(project.resolve("main.loom"), quiet())).doesNotContainKey("cut");
    }

    /** The three evaluation stages, as the README line stands for them. */
    private static final class GuideJourneyStages {
        static final List<String> EVALUATION = JourneyStages.EVALUATION_STAGES;
    }
}
