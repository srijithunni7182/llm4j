package io.github.llm4j.getviral.engine;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.audit.FileAuditLogger;
import io.github.llm4j.engram.core.ContextIntelligenceAgent;
import io.github.llm4j.engram.core.LLMContextIntelligenceAgent;
import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.llm.StudioModels;
import io.github.llm4j.getviral.media.ImageGenerator;
import io.github.llm4j.getviral.media.MediaAsset;
import io.github.llm4j.getviral.media.MediaLibrary;
import io.github.llm4j.getviral.media.MediaStudio;
import io.github.llm4j.getviral.media.VeoClient;
import io.github.llm4j.getviral.quality.QualityGate;
import io.github.llm4j.getviral.rag.KnowledgeBase;
import io.github.llm4j.getviral.studio.StudioRun;
import io.github.llm4j.getviral.tools.DatamuseWordLabTool;
import io.github.llm4j.getviral.tools.GenerateImageTool;
import io.github.llm4j.getviral.tools.GenerateVideoClipTool;
import io.github.llm4j.getviral.tools.RenderReelTool;
import io.github.llm4j.getviral.tools.HackerNewsPulseTool;
import io.github.llm4j.getviral.tools.HolidayMomentsTool;
import io.github.llm4j.getviral.tools.InstagramGraphClient;
import io.github.llm4j.getviral.tools.InstagramPublishTool;
import io.github.llm4j.getviral.tools.InstagramQuotaTool;
import io.github.llm4j.getviral.tools.MastodonTrendsTool;
import io.github.llm4j.getviral.tools.OpenverseBrollTool;
import io.github.llm4j.getviral.tools.ReadPageTool;
import io.github.llm4j.getviral.tools.TrendingAudioTool;
import io.github.llm4j.getviral.tools.ViralPlaybookTool;
import io.github.llm4j.getviral.tools.WebSearchTool;
import io.github.llm4j.getviral.tools.WikipediaFactCheckTool;
import io.github.llm4j.getviral.tools.WikipediaTrendingTool;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Runs one GetViral brief through the whole stack:
 * <b>Loom</b> orchestrates the workflow, <b>ai-agent4j</b> ReAct agents do the work with public-API
 * tools, <b>ai-agent4j-addons</b> embeddings power RAG, <b>Engram</b> remembers the creator across
 * runs, and <b>eval4j</b> grades the result before it is shown.
 */
public class GetViralEngine {

    public static final String SCRIPT = "/getviral/getviral.loom";
    public static final String WORKFLOW = "GetViral";

    /** What the creator types into the studio. */
    /**
     * What the creator types into the studio. {@code ownerId} scopes memory and voice samples to an
     * account (the hosted app passes the user id); without it the handle is used, as in local/CLI runs.
     */
    public record Brief(String idea, String handle, String niche, String tone, String region, List<String> voiceSamples,
                        String ownerId) {

        public Brief(String idea, String handle, String niche, String tone, String region, List<String> voiceSamples) {
            this(idea, handle, niche, tone, region, voiceSamples, null);
        }

        /** The key Engram memory, voice samples and RAG filters are stored under. */
        public String memoryKey() {
            return ownerId != null && !ownerId.isBlank() ? ownerId : handle;
        }

        public Brief {
            idea = idea == null ? "" : idea.strip();
            handle = handle == null || handle.isBlank() ? "creator" : handle.strip().replaceFirst("^@", "");
            niche = niche == null || niche.isBlank() ? "lifestyle" : niche.strip();
            tone = tone == null || tone.isBlank() ? "warm and witty" : tone.strip();
            region = region == null || region.isBlank() ? "US" : region.strip().toUpperCase();
            voiceSamples = voiceSamples == null ? List.of() : List.copyOf(voiceSamples);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("idea", idea);
            map.put("handle", handle);
            map.put("niche", niche);
            map.put("tone", tone);
            map.put("region", region);
            return map;
        }
    }

    /** Everything a finished run produced — what the UI renders and the eval suite asserts on. */
    public record Outcome(StudioRun.Status status, Map<String, Object> pack, List<QualityGate.Badge> badges,
                          GetViralExecutor executor, PromptBook prompts) { }

    private final GetViralConfig config;
    private final long demoPaceMillis;

    public GetViralEngine(GetViralConfig config, long demoPaceMillis) {
        this.config = config;
        this.demoPaceMillis = demoPaceMillis;
    }

    private MediaLibrary.Sink mediaSink = MediaLibrary.Sink.NONE;

    /** Where finished media is mirrored (the hosted app uploads to cloud storage). */
    public void mediaSink(MediaLibrary.Sink sink) {
        this.mediaSink = sink != null ? sink : MediaLibrary.Sink.NONE;
    }

    public GetViralConfig config() {
        return config;
    }

    /** Instagram account a run publishes to; {@code null} fields mean an honest dry run. */
    public record Publishing(String igUserId, String igAccessToken, String igUsername) {
        public static final Publishing DRY_RUN = new Publishing(null, null, null);
    }

    public Outcome run(StudioRun run, Brief brief) {
        return run(run, brief, null);
    }

    /**
     * @param publishing per-run Instagram account (hosted multi-user mode); {@code null} uses the
     *                   IG_* environment variables, as in local single-user mode
     */
    public Outcome run(StudioRun run, Brief brief, Publishing publishing) {
        GetViralConfig runConfig = publishing == null ? config : config.withInstagram(publishing.igUserId(), publishing.igAccessToken());
        StudioModels models = new StudioModels(config, run, demoPaceMillis);
        KnowledgeBase knowledge = KnowledgeBase.shared(config);
        brief.voiceSamples().forEach(post -> knowledge.addVoiceSample(brief.memoryKey(), post));
        knowledge.ensureCreatorIndexed(brief.memoryKey());

        Map<String, Object> started = new LinkedHashMap<>();
        started.put("brief", brief.toMap());
        started.put("model", models.describe());
        started.put("embeddings", knowledge.embeddingLabel());
        started.put("vectorStore", knowledge.storeLabel());
        started.put("instagram", runConfig.instagramConfigured()
                ? (publishing != null && publishing.igUsername() != null ? "connected as @" + publishing.igUsername() : "connected")
                : "dry-run");
        started.put("publicApis", config.offlineApis() ? "offline samples" : "live (sample fallback)");
        started.put("webSearch", config.offlineApis() ? "offline"
                : config.geminiApiKey() != null ? "Google Search via Gemini + GDELT news + Wikipedia"
                : "GDELT news + Wikipedia + DuckDuckGo (add a Gemini key for Google Search)");
        List<ImageGenerator> imageChain = MediaStudio.imageChain(config);
        started.put("images", MediaStudio.describe(imageChain));
        started.put("video", "Reel renderer " + config.reelWidth() + "x" + config.reelHeight()
                + (veoEnabled() ? " + Google Veo clips" : " (Veo clips off)"));
        MediaLibrary media = new MediaLibrary(config.dataDir(), run.id(), run, mediaSink);
        AtomicReference<GetViralExecutor> executorRef = new AtomicReference<>();
        Supplier<Map<String, Object>> workflowVars = () -> executorRef.get() == null
                ? Map.of() : executorRef.get().getContext().getAll();
        run.emit("run_started", started);

        PromptBook prompts = new PromptBook(run);
        GetViralExecutor executor = new GetViralExecutor(loadScript(),
                tools(run, knowledge, brief, media, imageChain, workflowVars, runConfig), models, run,
                prompts, config.maxRevisions());
        executorRef.set(executor);
        CreatorMemory memory = new CreatorMemory(config.dataDir(), brief.memoryKey(), intelligence(models), run);
        executor.setMemoryEngine(memory);
        executor.setHumanInterface(new StudioHumanInterface(run, executor::getContext));
        executor.setAuditLogger(new FileAuditLogger(auditFile(run)));
        QualityGate gate = new QualityGate(models.createClient("judge"), run);
        AtomicReference<CompletableFuture<List<QualityGate.Badge>>> grading =
                new AtomicReference<>();
        executor.onShip(() -> grading.compareAndSet(null, CompletableFuture.supplyAsync(() -> {
            Map<String, Object> ctx = executor.getContext().getAll();
            Map<String, Object> platforms = new LinkedHashMap<>();
            platforms.put("x", ctx.get("xPack"));
            platforms.put("reel", ctx.get("reelPack"));
            platforms.put("youtube", ctx.get("youtubePack"));
            Map<String, Object> grounding = new LinkedHashMap<>();
            grounding.put("trends", ctx.get("trendReport"));
            grounding.put("research", ctx.get("researchDossier"));
            grounding.put("plan", ctx.get("gamePlan"));
            return gate.evaluate(platforms, String.valueOf(ctx.get("hookChoice")), grounding(grounding));
        })));

        try {
            executor.initialize();
            executor.executeWorkflow(WORKFLOW, Map.of(
                    "creatorIdea", brief.idea(),
                    "creatorHandle", brief.handle(),
                    "creatorNiche", brief.niche(),
                    "creatorTone", brief.tone(),
                    "creatorRegion", brief.region()));
        } catch (RuntimeException e) {
            run.emit("error", Map.of("message", String.valueOf(e.getMessage())));
            run.status(StudioRun.Status.FAILED);
            return new Outcome(StudioRun.Status.FAILED, Map.of(), List.of(), executor, prompts);
        } finally {
            executor.shutdown();
        }

        // Loom returns "" for unset variables, so read the raw map to tell "absent" from "empty".
        Map<String, Object> ctx = executor.getContext().getAll();
        Object safety = ctx.get("safetyNotice");
        if (safety != null && !safety.toString().isBlank()) {
            run.emit("blocked", Map.of("reason", "Personal data detected in the brief", "notice", safety));
            run.status(StudioRun.Status.BLOCKED);
            return new Outcome(StudioRun.Status.BLOCKED, Map.of("safetyNotice", safety), List.of(), executor, prompts);
        }

        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("hook", ctx.get("hookChoice"));
        pack.put("x", ctx.get("xPack"));
        pack.put("reel", ctx.get("reelPack"));
        pack.put("youtube", ctx.get("youtubePack"));
        pack.put("plan", ctx.get("gamePlan"));
        pack.put("trends", ctx.get("trendReport"));
        Object research = ctx.get("researchDossier");
        if (research != null && !research.toString().isBlank()) pack.put("research", research);
        pack.put("critic", ctx.get("criticReport"));
        pack.put("casting", ctx.get("castingSheet"));
        Object receipt = ctx.get("publishReceipt");
        if (receipt != null && !receipt.toString().isBlank()) pack.put("publish", receipt);

        String hook = String.valueOf(pack.get("hook"));
        memory.remember("@" + brief.handle() + " picked the hook \"" + hook + "\" for \"" + brief.idea() + "\"",
                0.8, null);

        CompletableFuture<List<QualityGate.Badge>> pending = grading.get();
        List<QualityGate.Badge> badges = pending != null ? pending.join()
                : gate.evaluate(Map.of("x", pack.get("x"), "reel", pack.get("reel"), "youtube", pack.get("youtube")),
                        hook, grounding(pack));
        pack.put("quality", badges.stream().map(QualityGate.Badge::toMap).toList());
        pack.put("prompts", prompts.all());
        pack.put("media", media.assets().stream().map(MediaAsset::toMap).toList());
        Object visuals = ctx.get("visualPack");
        if (visuals != null && !visuals.toString().isBlank()) pack.put("visuals", visuals);
        Object video = ctx.get("videoPack");
        if (video != null && !video.toString().isBlank()) pack.put("video", video);
        run.emit("pack", pack);
        run.status(StudioRun.Status.DONE);
        return new Outcome(StudioRun.Status.DONE, pack, badges, executor, prompts);
    }

    /** A creator's 🔥 / 👎 on a platform becomes an Engram memory; a newer rating shadows the old one. */
    public void feedback(String handle, String platform, boolean loved, String detail) {
        String content = (loved ? "The creator LOVED the " : "The creator did NOT like the ") + platform
                + " output" + (detail == null || detail.isBlank() ? "" : " (" + detail.strip() + ")")
                + (loved ? " — do more like this." : " — try a different approach next time.");
        new CreatorMemory(config.dataDir(), handle, null, null).remember(content, 0.9, "feedback:" + platform);
    }

    public List<Map<String, Object>> memories(String handle) {
        return CreatorMemory.list(CreatorMemory.fileFor(config.dataDir(), handle.replaceFirst("^@", "")));
    }

    /** Google Search grounding runs on Gemini: the studio model when it is Gemini, else a Flash model. */
    private String searchModel() {
        String override = System.getenv("GETVIRAL_SEARCH_MODEL");
        if (override != null && !override.isBlank()) return override.strip();
        return config.mode() == GetViralConfig.Mode.GEMINI ? config.model() : "gemini-2.5-flash";
    }

    private boolean veoEnabled() {
        return config.veoEnabled() && config.geminiApiKey() != null;
    }

    private ToolRegistry tools(StudioRun run, KnowledgeBase knowledge, Brief brief, MediaLibrary media,
                               List<ImageGenerator> imageChain, Supplier<Map<String, Object>> workflowVars,
                               GetViralConfig runConfig) {
        boolean offline = config.offlineApis();
        InstagramGraphClient instagram = new InstagramGraphClient(runConfig);
        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("TrendingNow", new WikipediaTrendingTool(offline, run));
        tools.put("HackerNewsPulse", new HackerNewsPulseTool(offline, run));
        tools.put("TrendingHashtags", new MastodonTrendsTool(offline, run));
        tools.put("MomentCalendar", new HolidayMomentsTool(offline, run));
        tools.put("FactCheck", new WikipediaFactCheckTool(offline, run));
        tools.put("WebSearch", new WebSearchTool(offline, run, config.geminiApiKey(), searchModel()));
        tools.put("ReadPage", new ReadPageTool(offline, run));
        tools.put("WordLab", new DatamuseWordLabTool(offline, run));
        tools.put("TrendingAudio", new TrendingAudioTool(offline, run));
        tools.put("BrollFinder", new OpenverseBrollTool(offline, run));
        tools.put("ViralPlaybook", new ViralPlaybookTool(knowledge, brief.memoryKey(), run));
        tools.put("InstagramQuota", new InstagramQuotaTool(instagram));
        tools.put("InstagramPublish", new InstagramPublishTool(instagram, run));
        tools.put("GenerateImage", new GenerateImageTool(imageChain, media));
        tools.put("RenderReel", new RenderReelTool(media, workflowVars, config.reelWidth(), config.reelHeight(), 24));
        tools.put("GenerateVideoClip", new GenerateVideoClipTool(
                veoEnabled() ? new VeoClient(config.geminiApiKey(), config.veoModel()) : null, media));
        ToolRegistry registry = new ToolRegistry();
        tools.forEach(registry::register);
        return registry;
    }

    private ContextIntelligenceAgent intelligence(StudioModels models) {
        if (config.mode() == GetViralConfig.Mode.DEMO) {
            return new DemoInsightAgent();
        }
        LLMClient client = models.createClient("studio");
        return new LLMContextIntelligenceAgent(client);
    }

    private static List<String> grounding(Map<String, Object> pack) {
        List<String> context = new ArrayList<>();
        if (pack.get("trends") != null) context.add(pack.get("trends").toString());
        if (pack.get("research") != null) context.add(pack.get("research").toString());
        if (pack.get("plan") instanceof Map<?, ?> plan && plan.get("key_facts") instanceof List<?> facts) {
            context.add(facts.stream().map(String::valueOf).collect(Collectors.joining("\n")));
        }
        return context;
    }

    private Path auditFile(StudioRun run) {
        Path dir = config.dataDir().resolve("audit");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create audit directory " + dir, e);
        }
        return dir.resolve("run-" + run.id() + ".jsonl");
    }

    public static LoomScript loadScript() {
        try (InputStream in = GetViralEngine.class.getResourceAsStream(SCRIPT)) {
            if (in == null) throw new IllegalStateException("Missing " + SCRIPT);
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new LoomParser(new Lexer(source).tokenize()).parseScript();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SCRIPT, e);
        }
    }
}
