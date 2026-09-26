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
import io.github.llm4j.getviral.quality.ArtifactChecks;
import io.github.llm4j.getviral.tools.QualityGateTool;
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
    private CastingHistory castingHistory;
    private final Map<String, java.util.function.UnaryOperator<Tool>> toolDecorators = new LinkedHashMap<>();

    /** Test hook: wraps a tool (e.g. to simulate a Reel render that comes out broken). */
    void decorateTool(String name, java.util.function.UnaryOperator<Tool> decorator) {
        toolDecorators.put(name, decorator);
    }

    /** Where past castings are kept (the hosted app uses the database; default: files in the data dir). */
    public void castingHistory(CastingHistory history) {
        this.castingHistory = history;
    }

    public CastingHistory castingHistory() {
        return castingHistory != null ? castingHistory : CastingHistory.files(config.dataDir());
    }

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
        return run(run, brief, publishing, io.github.llm4j.loom.runtime.RunJournal.inMemory());
    }

    /**
     * Runs — or resumes — a pack. Every step is recorded in {@code journal}; when a human is needed
     * the run returns {@code WAITING_FOR_HUMAN} without holding a thread. Record the answer in the
     * journal and call this again with the same journal: finished steps are replayed, not re-run.
     */
    public Outcome run(StudioRun run, Brief brief, Publishing publishing, io.github.llm4j.loom.runtime.RunJournal journal) {
        boolean resuming = !journal.all().isEmpty();
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
        if (resuming) media.restore(run.events());
        AtomicReference<GetViralExecutor> executorRef = new AtomicReference<>();
        Supplier<Map<String, Object>> workflowVars = () -> executorRef.get() == null
                ? Map.of() : executorRef.get().getContext().getAll();
        if (resuming) run.emit("resumed", Map.of("steps", journal.all().size()));
        else run.emit("run_started", started);

        PromptBook prompts = new PromptBook(run);
        // The quality gate the Showrunner reviews the build against: file inspection + platform limits +
        // originality + eval4j judges, per specialist.
        QualityGate gate = new QualityGate(models.createClient("judge"), run);
        QualityGateTool qualityTool = new QualityGateTool(new ArtifactChecks(media, workflowVars), gate, workflowVars, run);
        GetViralExecutor executor = new GetViralExecutor(loadScript(),
                tools(run, knowledge, brief, media, imageChain, workflowVars, runConfig, qualityTool), models, run,
                prompts, config.maxRevisions());
        executor.qualityGate(qualityTool);
        executorRef.set(executor);
        // Originality: brief the Showrunner with this creator's past castings, deal lenses and visual styles
        // they haven't used, and check new castings and YouTube packages against the old ones.
        CastingHistory history = castingHistory();
        List<CastingHistory.PastCasting> past = history.recent(brief.memoryKey(), CastingHistory.KEEP);
        long seed = run.id().hashCode() ^ System.nanoTime();
        List<String> lensOptions = CreativeLenses.deal(past.stream().limit(8).map(CastingHistory.PastCasting::lens).toList(), 4, seed);
        List<String> styleOptions = VisualStyles.deal(past.stream().limit(8)
                .flatMap(p -> java.util.stream.Stream.of(p.visualStyle(), p.artStyle())).toList(), 4, seed + 1);
        executor.originality(new OriginalityGate(past, brief.idea(), brief.niche()));
        Map<String, Object> creative = new LinkedHashMap<>();
        creative.put("pastCastings", past.size());
        creative.put("lensOptions", lensOptions);
        creative.put("styleOptions", styleOptions);
        if (!resuming) run.emit("creative_brief", creative);

        CreatorMemory memory = new CreatorMemory(config.dataDir(), brief.memoryKey(), intelligence(models), run);
        executor.setMemoryEngine(memory);
        executor.setHumanInterface(new StudioHumanInterface(run, executor::getContext));
        executor.setAuditLogger(new FileAuditLogger(auditFile(run)));
        executor.setJournal(journal);

        try {
            executor.initialize();
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("creatorIdea", brief.idea());
            inputs.put("creatorHandle", brief.handle());
            inputs.put("creatorNiche", brief.niche());
            inputs.put("creatorTone", brief.tone());
            inputs.put("creatorRegion", brief.region());
            inputs.put("pastCastings", CastingHistory.brief(past));
            inputs.put("lensOptions", String.join(" | ", lensOptions));
            inputs.put("styleOptions", String.join(" | ", styleOptions));
            inputs.put("pastYouTube", youtubeHistory(past));
            executor.executeWorkflow(WORKFLOW, inputs);
        } catch (io.github.llm4j.loom.runtime.RunSuspended waiting) {
            // The question has been announced; nothing holds a thread until it is answered.
            return new Outcome(StudioRun.Status.WAITING_FOR_HUMAN, Map.of(), List.of(), executor, prompts);
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
        recordCasting(history, brief, run, ctx, hook);
        memory.remember("@" + brief.handle() + " picked the hook \"" + hook + "\" for \"" + brief.idea() + "\"",
                0.8, null);

        // The Showrunner's last build review (journaled, so it survives a resume) decides the outcome.
        Map<?, ?> review = ctx.get("qualityReport") instanceof Map<?, ?> r ? r : Map.of();
        List<QualityGate.Badge> badges = badges(review);
        pack.put("quality", badges.stream().map(QualityGate.Badge::toMap).toList());
        pack.put("prompts", prompts.all());
        pack.put("media", media.assets().stream().map(MediaAsset::toMap).toList());
        Object visuals = ctx.get("visualPack");
        if (visuals != null && !visuals.toString().isBlank()) pack.put("visuals", visuals);
        Object video = ctx.get("videoPack");
        if (video != null && !video.toString().isBlank()) pack.put("video", video);
        if (!review.isEmpty()) pack.put("build", review);
        run.emit("pack", pack);

        // The run only counts as done when every artifact for every platform passes the gate.
        if (!"COMPLETE".equals(review.get("verdict"))) {
            List<String> failing = new ArrayList<>();
            for (String area : io.github.llm4j.getviral.quality.BuildReview.AREAS) {
                if ("FIX".equals(review.get(area))) failing.add(area + " (" + review.get(area + "_fix") + ")");
            }
            if (failing.isEmpty()) failing.add("the build was never reviewed");
            run.emit("error", Map.of("message", "Not every artifact passed the quality gate after the Showrunner's "
                    + "review rounds, so this pack isn't marked done: " + String.join(" · ", failing)));
            run.status(StudioRun.Status.FAILED);
            return new Outcome(StudioRun.Status.FAILED, pack, badges, executor, prompts);
        }
        run.status(StudioRun.Status.DONE);
        return new Outcome(StudioRun.Status.DONE, pack, badges, executor, prompts);
    }

    /** The eval4j badges recorded with the last build review. */
    private static List<QualityGate.Badge> badges(Map<?, ?> review) {
        List<QualityGate.Badge> out = new ArrayList<>();
        if (review.get("badges") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> b) {
                    out.add(new QualityGate.Badge(text(b.get("name")), text(b.get("platform")), number(b.get("score")),
                            number(b.get("threshold")), Boolean.TRUE.equals(b.get("passed")), text(b.get("reason"))));
                }
            }
        }
        return out;
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String youtubeHistory(List<CastingHistory.PastCasting> past) {
        List<String> lines = new ArrayList<>();
        for (CastingHistory.PastCasting p : past) {
            if (p.youtubeTitles() == null || p.youtubeTitles().isEmpty()) continue;
            lines.add("- \"" + p.idea() + "\": titles \"" + String.join("\" / \"", p.youtubeTitles()) + "\""
                    + (p.thumbnailConcept() == null || p.thumbnailConcept().isBlank() ? "" : "; thumbnail: " + p.thumbnailConcept()));
        }
        return lines.isEmpty() ? "(none yet)" : String.join("\n", lines);
    }

    /** Saves what this run chose, so the next casting is briefed with it and checked against it. */
    private static void recordCasting(CastingHistory history, Brief brief, StudioRun run, Map<String, Object> ctx, String hook) {
        // castingSheet is the full casting (after any originality re-cast); the critic's re-casts go to revisionCast.
        if (!(ctx.get("castingSheet") instanceof Map<?, ?> sheet)) return;
        Map<?, ?> visuals = ctx.get("visualPack") instanceof Map<?, ?> v ? v : Map.of();
        Map<?, ?> yt = ctx.get("youtubePack") instanceof Map<?, ?> y ? y : Map.of();
        Map<String, String> prompts = new LinkedHashMap<>();
        if (sheet.get("prompts") instanceof Map<?, ?> p) p.forEach((k, val) -> prompts.put(String.valueOf(k), String.valueOf(val)));
        try {
            history.record(brief.memoryKey(), new CastingHistory.PastCasting(run.id(), java.time.Instant.now().toString(),
                    brief.idea(), brief.niche(), text(sheet.get("run_title")), text(sheet.get("lens")),
                    text(sheet.get("creative_direction")), strings(sheet.get("signature_ideas")), text(sheet.get("visual_style")),
                    text(visuals.get("style")), strings(yt.get("titles")), text(yt.get("thumbnail_concept")), hook, prompts));
        } catch (RuntimeException e) {
            run.emit("note", Map.of("text", "Couldn't save this casting to the creator's history: " + e.getMessage()));
        }
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
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
                               GetViralConfig runConfig, QualityGateTool qualityTool) {
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
        tools.put("QualityGate", qualityTool);
        tools.put("GenerateVideoClip", new GenerateVideoClipTool(
                veoEnabled() ? new VeoClient(config.geminiApiKey(), config.veoModel()) : null, media));
        toolDecorators.forEach((name, decorate) -> tools.computeIfPresent(name, (k, tool) -> decorate.apply(tool)));
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
