package io.github.llm4j.hexamind.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.prompt.FileSystemPromptRegistry;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.judge.FileSystemJudgeCache;
import io.github.llm4j.eval.judge.JudgeCache;
import io.github.llm4j.multiagent.config.AgentConfiguration;
import io.github.llm4j.multiagent.model.AgentParticipant;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything the live evaluation tests share: the two models (real, or fake with {@code
 * -Deval.fake=true}), the spend guard, the agents built by the application's own {@code
 * AgentConfiguration} with recorded search, the judge cache, and the run declarations.
 *
 * <p>Keys come from the environment only: {@code GEMINI_API_KEY} (or {@code google.api.key}) and {@code
 * ANTHROPIC_API_KEY}.
 */
public final class EvalSupport {

    public static final boolean FAKE = Boolean.getBoolean("eval.fake");
    public static final double JUDGE_THRESHOLD = 0.7;
    public static final String AGENT_MODEL = "gemini-3.5-flash";
    public static final String JUDGE_MODEL = "claude-sonnet-5-5";
    public static final String JUDGE_ID = "judge-main";
    public static final String CALIBRATION_JUDGE_ID = "judge-calibration";
    public static final Path OUT = Path.of("target", "eval");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final SpendGuard GUARD =
            new SpendGuard(
                    Path.of("eval", "prices.properties"),
                    Double.parseDouble(System.getProperty("eval.capUsd", "10")),
                    Integer.getInteger("eval.maxOutputTokens", 20_000));

    public static final ReplayCache REPLAY = new ReplayCache(OUT.resolve("replay"));

    private static LLMClient agentClient;
    private static LLMClient judgeClient;
    private static Holder holder;
    private static List<AgentParticipant> swarm;
    private static boolean declared;

    static {
        // the shell script reads this to print what the run really cost and whether the guard stopped it
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        java.nio.file.Files.createDirectories(OUT);
                                        java.nio.file.Files.writeString(
                                                OUT.resolve("spend.json"),
                                                String.format(
                                                        java.util.Locale.ROOT,
                                                        "{\"fake\":%b,\"calls\":%d,\"tokensIn\":%d,\"tokensOut\":%d,\"usd\":%.4f,\"stopped\":%s}%n",
                                                        FAKE,
                                                        GUARD.calls(),
                                                        GUARD.tokensIn(),
                                                        GUARD.tokensOut(),
                                                        GUARD.spentUsd(),
                                                        GUARD.reason() == null ? "null" : JSON.writeValueAsString(GUARD.reason())));
                                    } catch (java.io.IOException ignored) {
                                        // best effort
                                    }
                                }));
    }

    private EvalSupport() {}

    /**
     * Spaces Gemini calls out (a free-tier key allows only a few requests a minute) and retries rate-limit
     * errors with a growing pause. Five rate-limit errors in a row stop the whole run: the quota is gone.
     */
    static final class Throttled implements LLMClient {
        private final LLMClient delegate;
        private final long gapMillis;
        private long next;
        private int consecutive429;

        Throttled(LLMClient delegate, int perMinute) {
            this.delegate = delegate;
            this.gapMillis = 60_000L / Math.max(1, perMinute);
        }

        private synchronized void pace() {
            long now = System.currentTimeMillis();
            long wait = next - now;
            next = Math.max(now, next) + gapMillis;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest request) {
            RuntimeException last = null;
            for (int attempt = 0; attempt < 6; attempt++) {
                pace();
                try {
                    io.github.llm4j.model.LLMResponse r = delegate.chat(request);
                    synchronized (this) {
                        consecutive429 = 0;
                    }
                    return r;
                } catch (RuntimeException e) {
                    String m = String.valueOf(e) + " " + e.getCause();
                    if (!(m.contains("429") || m.toLowerCase().contains("rate") || m.toLowerCase().contains("quota")
                            || m.contains("RESOURCE_EXHAUSTED"))) {
                        throw e;
                    }
                    last = e;
                    boolean quotaGone;
                    synchronized (this) {
                        consecutive429++;
                        quotaGone = consecutive429 >= 5;
                    }
                    if (quotaGone) {
                        GUARD.stop("Gemini kept returning rate-limit errors (free-tier quota is probably used up)");
                        throw e;
                    }
                    try {
                        Thread.sleep(Math.min(120_000L, 15_000L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            throw last;
        }

        @Override
        public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest request) {
            return java.util.stream.Stream.of(chat(request));
        }
    }

    /** The agents' search: the scenario's own recorded results if it has them, else the recorded library. */
    static final class Holder implements Tool {
        private final SearchLibrary library = SearchLibrary.load();
        private volatile SearchLibrary.RecordedSearch current = (SearchLibrary.RecordedSearch) library.tool("WebSearch", List.of(), List.of());

        void use(List<String> scenarioSnippets) {
            current = (SearchLibrary.RecordedSearch) library.tool("WebSearch", scenarioSnippets, List.of());
        }

        @Override
        public String getName() {
            return "WebSearch";
        }

        @Override
        public String getDescription() {
            return current.getDescription();
        }

        @Override
        public String execute(Map<String, Object> args) {
            return current.execute(args);
        }
    }

    private static String key(String env, String prop) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            v = System.getProperty(prop);
        }
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "no " + env + " in the environment (or -D" + prop + "); use -Deval.fake=true to run for free");
        }
        return v;
    }

    public static synchronized LLMClient agentClient() {
        if (agentClient == null) {
            LLMClient base =
                    FAKE
                            ? FakeModels.agent()
                            : new DefaultLLMClient(
                                    new GoogleProvider(
                                            LLMConfig.builder()
                                                    .apiKey(key("GEMINI_API_KEY", "google.api.key"))
                                                    .defaultModel(AGENT_MODEL)
                                                    .build()));
            agentClient = GUARD.guard(FAKE ? base : new Throttled(base, Integer.getInteger("eval.geminiRpm", 10)), AGENT_MODEL);
        }
        return agentClient;
    }

    public static synchronized LLMClient judgeClient() {
        if (judgeClient == null) {
            LLMClient base =
                    FAKE
                            ? FakeModels.judge()
                            : new DefaultLLMClient(
                                    new AnthropicProvider(
                                            LLMConfig.builder()
                                                    .apiKey(key("ANTHROPIC_API_KEY", "anthropic.api.key"))
                                                    .defaultModel(JUDGE_MODEL)
                                                    .build(),
                                            "low"));
            judgeClient = GUARD.guard(base, JUDGE_MODEL);
        }
        return judgeClient;
    }

    /** A separate cache for the calibration pass, so its three samples per case are never served from stage 2. */
    public static JudgeCache calibrationCache() {
        return FileSystemJudgeCache.at(OUT.resolve(FAKE ? "judge-cache-calibration-fake" : "judge-cache-calibration"));
    }

    public static JudgeCache judgeCache() {
        return FileSystemJudgeCache.at(OUT.resolve(FAKE ? "judge-cache-fake" : "judge-cache"));
    }

    public static PromptRegistry prompts() {
        return new FileSystemPromptRegistry(Path.of("src/main/resources/prompts.yaml"));
    }

    /** The six agents, built by the application's own configuration with recorded search. */
    public static synchronized List<AgentParticipant> swarm() {
        if (swarm == null) {
            holder = new Holder();
            Holder h = holder;
            AgentConfiguration cfg =
                    new AgentConfiguration() {
                        @Override
                        protected Tool createWebSearchTool() {
                            return h;
                        }
                    };
            swarm = cfg.agents(agentClient(), prompts());
        }
        return swarm;
    }

    public static AgentParticipant agent(String name) {
        for (AgentParticipant p : swarm()) {
            if (p.getName().toLowerCase().replace("dr. ", "").equals(name.toLowerCase())) {
                return p;
            }
        }
        throw new IllegalArgumentException("no agent " + name);
    }

    /** Declares judge, agents and datasets so the report shows them. Safe to call repeatedly. */
    public static synchronized void declare() {
        if (declared) {
            return;
        }
        declared = true;
        EvalRun run = EvalRun.get();
        run.declareJudge(JUDGE_ID, FAKE ? "fake" : "anthropic", JUDGE_MODEL, 0.0, 1, "MEAN");
        run.declareJudge(CALIBRATION_JUDGE_ID, FAKE ? "fake" : "anthropic", JUDGE_MODEL, 0.7, 3, "MEAN");
        for (String a : GoldenDataset.AGENTS) {
            run.declareAgent(a, FAKE ? "fake" : "google", AGENT_MODEL, "agent_analyze", "v1", List.of("WebSearch", "CurrentDateTime"));
            run.declareDataset("golden-" + a, a + ".yaml", "eval/golden/" + a + ".yaml", GoldenDataset.agent(a));
        }
        run.declareDataset("golden-prompts", "prompts.yaml", "eval/golden/prompts.yaml", GoldenDataset.prompts());
        run.declareDataset("golden-workflow", "workflow.yaml", "eval/golden/workflow.yaml", GoldenDataset.workflow());
    }

    /**
     * Judges {@code output} once against the scenario's rubric, then files that single verdict under each
     * quality dimension the scenario lists, so the report's dimensions are populated from the dataset.
     * {@code withFixture} gives the judge the recorded search snippets as retrieval context.
     */
    public static io.github.llm4j.eval.judge.JudgeVerdict judgeRubric(
            String metricName,
            EvalScenario s,
            Object output,
            boolean withFixture,
            JudgeCache cache,
            String judgeId,
            int samples) {
        var b =
                io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged(metricName)
                        .criteria(String.join("\n", GoldenDataset.rubric(s)))
                        .input(s.input())
                        .judge(judgeClient())
                        .samples(samples)
                        .cache(cache)
                        .judgeIdentifier(judgeId)
                        .threshold(JUDGE_THRESHOLD);
        List<String> evidence = withFixture ? evidence(s, output) : List.of();
        if (!evidence.isEmpty()) {
            b.retrievalContext(evidence);
        }
        var verdict = b.build().evaluate(output);
        var details =
                new io.github.llm4j.eval.report.EvalDetails(
                        s.input(),
                        output instanceof AgentResult r ? r.getFinalAnswer() : String.valueOf(output),
                        null,
                        evidence.isEmpty() ? null : evidence,
                        0L);
        List<String> dims = s.dimensions() == null || s.dimensions().isEmpty() ? java.util.Collections.singletonList(null) : s.dimensions();
        for (String dim : dims) {
            io.github.llm4j.eval.export.MetricRef ref =
                    new io.github.llm4j.eval.export.MetricRef(
                            dim == null ? "rubric" : "rubric-" + dim,
                            metricName,
                            io.github.llm4j.eval.export.Kind.JUDGE,
                            "agents",
                            "answers",
                            dim,
                            null,
                            null,
                            null);
            io.github.llm4j.eval.report.EvalRecorder.record(
                    ref, verdict.score(), JUDGE_THRESHOLD, verdict.reason(), judgeId, details);
        }
        return verdict;
    }

    /** What the agent was actually shown: the observations of its WebSearch steps (else the scenario's fixture). */
    static List<String> evidence(EvalScenario s, Object output) {
        if (output instanceof AgentResult r) {
            List<String> seen = new java.util.ArrayList<>();
            for (AgentResult.AgentStep st : r.getSteps()) {
                if ("WebSearch".equals(st.getAction()) && st.getObservation() != null) {
                    seen.add(st.getObservation());
                }
            }
            if (!seen.isEmpty()) {
                return seen;
            }
        }
        return fixture(s);
    }

    /** The recorded search snippets of a scenario (its {@code retrievalContext}). */
    public static List<String> fixture(EvalScenario s) {
        return s.retrievalContext() == null ? List.of() : s.retrievalContext();
    }

    /**
     * Runs {@code task} on an agent with the scenario's recorded search, or replays the stored result of
     * the same scenario, prompt version and model.
     */
    public static AgentResult run(String agentName, EvalScenario s, String promptVersion, String task) {
        swarm();
        String fixture = String.join("\n", fixture(s));
        String key = ReplayCache.key(s.id() + ":" + agentName, promptVersion, AGENT_MODEL + (FAKE ? ":fake" : ""), fixture + "\n" + task);
        var hit = REPLAY.get(key);
        if (hit.isPresent()) {
            return fromJson(hit.get());
        }
        holder.use(fixture(s));
        AgentResult r = agent(agentName).getAgent().run(task);
        REPLAY.put(key, toJson(r));
        return r;
    }

    /** One plain model call (no tools, no persona), replayed when the same call was made before. */
    public static String plain(String system, String user) {
        String key = ReplayCache.key("plain", "", AGENT_MODEL + (FAKE ? ":fake" : ""), system + "\u0000" + user);
        var hit = REPLAY.get(key);
        if (hit.isPresent()) {
            return hit.get();
        }
        String out =
                agentClient()
                        .chat(
                                io.github.llm4j.model.LLMRequest.builder()
                                        .messages(
                                                List.of(
                                                        io.github.llm4j.model.Message.system(system),
                                                        io.github.llm4j.model.Message.user(user)))
                                        .build())
                        .getContent();
        REPLAY.put(key, out);
        return out;
    }

    /** How many times the last scenario searched (for efficiency checks). */
    public static int searches() {
        return holder == null ? 0 : holder.current.calls();
    }

    static String toJson(AgentResult r) {
        ObjectNode n = JSON.createObjectNode();
        n.put("answer", r.getFinalAnswer());
        n.put("completed", r.isCompleted());
        n.put("iterations", r.getIterations());
        ArrayNode steps = n.putArray("steps");
        for (AgentResult.AgentStep st : r.getSteps()) {
            ObjectNode o = steps.addObject();
            o.put("thought", st.getThought());
            o.put("action", st.getAction());
            o.put("input", st.getActionInput());
            o.put("observation", st.getObservation());
        }
        return n.toString();
    }

    static AgentResult fromJson(String s) {
        try {
            JsonNode n = JSON.readTree(s);
            AgentResult.Builder b =
                    AgentResult.builder()
                            .finalAnswer(n.path("answer").asText())
                            .completed(n.path("completed").asBoolean())
                            .iterations(n.path("iterations").asInt());
            for (JsonNode o : n.path("steps")) {
                b.addStep(
                        new AgentResult.AgentStep(
                                text(o, "thought"), text(o, "action"), text(o, "input"), text(o, "observation")));
            }
            return b.build();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String text(JsonNode o, String f) {
        return o.hasNonNull(f) ? o.get(f).asText() : null;
    }
}
