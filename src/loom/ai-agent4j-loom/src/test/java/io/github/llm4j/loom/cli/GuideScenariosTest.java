package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.eval.judge.LlmJudgeCondition;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 37: the Guide's behaviour, judged (TEST_STRATEGY §4, decision D18). Each scenario sets up a
 * project, gives the Guide (the shared persona with the real {@code weave guide-mcp} tools) one
 * message, and checks the turn: by code first (which tools it used, what it must and must not
 * say, its length), then against a rubric judged by a different model. The default build replays
 * recorded answers and calls no model; {@code -Dguide.record=true} records them again (it costs
 * money, and is run by the owner). A recording made for another persona, tool list or scenario
 * fails, so a change that alters the Guide's behaviour is noticed.
 */
class GuideScenariosTest {

    @TempDir Path dir;

    static final String GUIDE_MODEL = "claude-haiku-5-5";
    static final String JUDGE_MODEL = "claude-sonnet-5-5";
    static final double THRESHOLD = 0.7;
    /** Model turns per Guide answer; the hosts allow more, but an answer needing more is too slow. */
    private static final int MAX_STEPS = 12;
    private static final Path FOLDER = Path.of("src/test/resources/guide-scenarios");
    private static final Path PERSONA = Path.of("../vscode-loom/guide/persona.md");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
    private static final boolean RECORD = Boolean.getBoolean("guide.record");
    /** Replays the Guide's recorded answers and asks the judge again: for a change of the rubric or the judge only. */
    private static final boolean REJUDGE = Boolean.getBoolean("guide.rejudge");
    /** What the judge is shown of what the Guide read, at most. */
    private static final int MAX_READ_CHARS = 14000;
    /** Changes when the judge's instructions change, so a recording judged under older ones is judged again. */
    private static final String JUDGE_FORM = "grounded-in-what-was-read-1";
    private static final String ONLY = System.getProperty("guide.only", "");

    @TestFactory
    Stream<DynamicTest> theGuideBehavesAsTheScenariosSay() throws Exception {
        JsonNode cases = new ObjectMapper(new YAMLFactory()).readTree(FOLDER.resolve("scenarios.yaml").toFile());
        String persona = Files.readString(PERSONA);
        List<DynamicTest> tests = new ArrayList<>();
        cases.fields().forEachRemaining(e -> {
            if (ONLY.isEmpty() || List.of(ONLY.split(",")).contains(e.getKey()))
                tests.add(DynamicTest.dynamicTest(e.getKey(), () -> run(e.getKey(), e.getValue(), persona)));
        });
        return tests.stream();
    }

    private void run(String id, JsonNode c, String persona) throws Exception {
        Path project = setUp(id, c);
        String fingerprint = guideFingerprint(persona, c);
        String judgeFingerprint = judgeFingerprint(c);
        Path recordingFile = FOLDER.resolve("recordings").resolve(id + ".json");
        RecordedClient guide;
        RecordedClient judge;
        JsonNode previous = Files.isRegularFile(recordingFile) ? JSON.readTree(recordingFile.toFile()) : null;
        if (RECORD) {
            DefaultLLMClientFactory models = new DefaultLLMClientFactory(GuideScenariosTest::env);
            guide = RecordedClient.recording(models.createClient(GUIDE_MODEL));
            judge = RecordedClient.recording(models.createClient(JUDGE_MODEL));
        } else {
            assertThat(previous).as("no recording for " + id + ": record with -Dguide.record=true").isNotNull();
            assertThat(previous.path("fingerprint").asText())
                    .as(id + " was recorded for another persona, tool list, scenario or Guide model: record it again with -Dguide.record=true")
                    .isEqualTo(fingerprint);
            guide = RecordedClient.replaying(previous.path("guide"));
            if (REJUDGE) {
                judge = RecordedClient.recording(new DefaultLLMClientFactory(GuideScenariosTest::env).createClient(JUDGE_MODEL));
            } else {
                assertThat(previous.path("judgeFingerprint").asText())
                        .as(id + " was judged under another rubric or judge: judge it again with -Dguide.rejudge=true (the Guide is replayed)")
                        .isEqualTo(judgeFingerprint);
                judge = RecordedClient.replaying(previous.path("judge"));
            }
        }

        List<String> called = new ArrayList<>();
        StringBuilder read = new StringBuilder();
        List<String> shown = new ArrayList<>();
        GuideMcpServer server = new GuideMcpServer(
                project.resolve("main.loom"), quietEnv(), new GuideEvents(project, CLOCK),
                new GuideLearner(dir.resolve("home-" + id), project, CLOCK),
                (action, args) -> {
                    shown.add(action);
                    return "Shown in the editor.";
                });
        ReActAgent agent = ReActAgent.builder()
                .llmClient(guide)
                .systemPrompt(persona)
                .addTools(tools(server, called, read))
                .toolCalling(ReActAgent.ToolCalling.NATIVE)
                .maxIterations(MAX_STEPS)
                .temperature(0.2)
                .build();
        AgentResult result = agent.run(c.path("message").asText());
        String answer = result.getFinalAnswer() == null ? "" : result.getFinalAnswer();

        String reading = read.length() <= MAX_READ_CHARS ? read.toString() : read.substring(0, MAX_READ_CHARS) + "\n… (cut)";
        String criteria = c.path("rubric").asText()
                + "\n\nThe developer said: " + c.path("message").asText()
                + "\nThe Guide called these tools: " + called
                + "\n\nWhat the Guide read in this turn, from llm4j's own documentation and project tools. An option, command or"
                + " construct that appears here is real, not invented; one that appears nowhere here and contradicts it may be invented:\n"
                + "<read>\n" + reading + "\n</read>"
                + "\n\nJudge only the Guide's final answer below against what a good answer does.";
        LlmJudgeCondition judged = LlmJudgeCondition.llmJudged(id).criteria(criteria).judge(judge).threshold(THRESHOLD).build();
        boolean passed = judged.matches(answer);

        if (RECORD || REJUDGE) {
            ObjectNode rec = JSON.createObjectNode();
            rec.put("fingerprint", fingerprint);
            rec.put("judgeFingerprint", judgeFingerprint);
            rec.put("guideModel", GUIDE_MODEL);
            rec.put("judgeModel", JUDGE_MODEL);
            rec.put("recordedAt", RECORD ? Instant.now().toString() : previous.path("recordedAt").asText());
            rec.put("judgedAt", Instant.now().toString());
            rec.put("promptTokens", (RECORD ? guide.promptTokens : previous.path("guidePromptTokens").asLong(previous.path("promptTokens").asLong())) + judge.promptTokens);
            rec.put("guidePromptTokens", RECORD ? guide.promptTokens : previous.path("guidePromptTokens").asLong(previous.path("promptTokens").asLong()));
            rec.put("completionTokens", (RECORD ? guide.completionTokens : 0) + judge.completionTokens);
            rec.put("answer", answer);
            rec.set("guide", RECORD ? guide.recorded() : previous.path("guide"));
            rec.set("judge", judge.recorded());
            Files.createDirectories(recordingFile.getParent());
            Files.writeString(recordingFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rec) + "\n");
        }
        if (!RECORD) assertThat(guide.allReplayed()).as(id + ": the Guide made fewer calls than recorded; record again").isTrue();

        for (JsonNode need : c.path("mustCall")) {
            List<String> anyOf = List.of(need.asText().split("\\s*\\|\\s*"));
            assertThat(called).as(id + " must call one of " + anyOf + "; answer: " + answer).containsAnyElementsOf(anyOf);
        }
        for (JsonNode never : c.path("mustNotCall"))
            assertThat(called).as(id + " must not call " + never.asText()).doesNotContain(never.asText());
        String lower = answer.toLowerCase(Locale.ROOT);
        for (JsonNode p : c.path("mustSay"))
            assertThat(Pattern.compile(p.asText(), Pattern.CASE_INSENSITIVE).matcher(answer).find()).as(id + " must say " + p + ": " + answer).isTrue();
        for (JsonNode p : c.path("mustNotSay"))
            assertThat(Pattern.compile(p.asText(), Pattern.CASE_INSENSITIVE).matcher(lower).find()).as(id + " must not say " + p + ": " + answer).isFalse();
        if (c.has("maxWords"))
            assertThat(answer.isBlank() ? 0 : answer.strip().split("\\s+").length).as(id + " is too long: " + answer).isLessThanOrEqualTo(c.path("maxWords").asInt());
        assertThat(called).as(id + " used no tool that changes anything").allMatch(t -> GuideMcpServer.TOOLS.stream().anyMatch(x -> x.name().equals(t)));
        assertThat(passed).as(id + " judged against the rubric: " + judged.description() + "\nanswer: " + answer).isTrue();
    }

    private Path setUp(String id, JsonNode c) throws Exception {
        Path p = dir.resolve(id);
        assertThat(WeaveCLI.commandLine().execute("init", c.path("template").asText("pipeline"), p.toString(), "--flat")).isZero();
        JsonNode setup = c.path("setup");
        var files = setup.path("files").fields();
        while (files.hasNext()) {
            var f = files.next();
            Path target = p.resolve(f.getKey());
            if (f.getValue().isNull()) deleteTree(target);
            else {
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.getValue().asText());
            }
        }
        var skips = setup.path("skips").fields();
        while (skips.hasNext()) {
            var s = skips.next();
            SkippedStages.skip(p, s.getKey(), s.getValue().asText(), "developer", CLOCK.instant());
        }
        GuideEvents events = new GuideEvents(p, CLOCK);
        for (JsonNode e : setup.path("events")) {
            java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
            e.path("data").fields().forEachRemaining(d -> data.put(d.getKey(), d.getValue().asText()));
            events.append(e.path("kind").asText(), e.path("stage").asText(""), e.path("subject").asText(""), data);
        }
        return p;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
    }

    /** The Guide's MCP tools as agent tools: each call goes through the server, exactly as a host's would. */
    private static List<Tool> tools(GuideMcpServer server, List<String> called, StringBuilder read) throws Exception {
        JsonNode list = server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").path("result").path("tools");
        List<Tool> out = new ArrayList<>();
        for (JsonNode t : list) {
            String name = t.path("name").asText();
            String description = t.path("description").asText();
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = JSON.convertValue(t.path("inputSchema"), Map.class);
            out.add(new Tool() {
                public String getName() { return name; }
                public String getDescription() { return description; }
                public Map<String, Object> getParametersSchema() { return schema; }
                public String execute(Map<String, Object> args) throws Exception {
                    called.add(name);
                    ObjectNode m = JSON.createObjectNode();
                    m.put("jsonrpc", "2.0");
                    m.put("id", 2);
                    m.put("method", "tools/call");
                    m.putObject("params").put("name", name).set("arguments", JSON.valueToTree(args == null ? Map.of() : args));
                    JsonNode r = server.handle(JSON.writeValueAsString(m));
                    if (r.has("error")) return "Error: " + r.path("error").path("message").asText();
                    String text = r.path("result").path("content").get(0).path("text").asText();
                    // what the Guide may draw on, for the judge: the documentation and the facts in full, the project in part
                    if (List.of("facts", "docs", "explain_finding").contains(name)) read.append("[").append(name).append(" ").append(args).append("]\n").append(text).append("\n\n");
                    else if (name.equals("project_summary")) read.append("[project_summary, start]\n").append(text, 0, Math.min(3000, text.length())).append("\n\n");
                    return text;
                }
            });
        }
        return out;
    }

    private static WeaveEnv quietEnv() {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return WeaveEnv.system().withStreams(sink, sink).withEnvLookup(k -> null);
    }

    /** Keys for a recording run: the environment, with the Anthropic key also read from where this machine keeps it. */
    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null && name.equals("ANTHROPIC_API_KEY")) v = System.getenv("SPIKE_ANTHROPIC_API_KEY");
        return v;
    }

    /** What the Guide sees: its persona, its tools, the scenario without the checks and the rubric, and its model. */
    private static String guideFingerprint(String persona, JsonNode scenario) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(persona.getBytes(StandardCharsets.UTF_8));
        // sorted: Map.of has no fixed order between runs
        for (GuideMcpServer.Tool t : GuideMcpServer.TOOLS)
            sha.update((t.name() + "|" + t.description() + "|" + new java.util.TreeMap<>(t.params())).getBytes(StandardCharsets.UTF_8));
        // only what the model sees changes its behaviour: not the checks made on its answer
        ObjectNode seen = (ObjectNode) scenario.deepCopy();
        seen.remove(List.of("mustCall", "mustNotCall", "mustSay", "mustNotSay", "maxWords", "rubric"));
        sha.update(JSON.writeValueAsBytes(seen));
        sha.update(GUIDE_MODEL.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha.digest());
    }

    /** What the judge sees that can change on its own: the rubric, the judge's instructions and its model. */
    private static String judgeFingerprint(JsonNode scenario) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update((scenario.path("rubric").asText() + "|" + JUDGE_FORM + "|" + JUDGE_MODEL).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha.digest());
    }
}
