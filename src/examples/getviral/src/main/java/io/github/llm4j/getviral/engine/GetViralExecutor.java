package io.github.llm4j.getviral.engine;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.getviral.studio.StudioRun;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loom's {@link HarnessExecutor}, specialised through its extension hooks:
 * <ul>
 *   <li>{@code customizeAgent} — streams every thought / tool call / observation to the studio and
 *       wires the Human-in-the-Loop approval gate.</li>
 *   <li>{@code agentForDelegate} — injects the Showrunner-written prompt for this agent, on the fly.</li>
 *   <li>{@code afterDelegateExecution} — versions new casting sheets, records results for eval4j,
 *       and caps the critic loop (a symbolic budget the neural critic can't talk its way past).</li>
 * </ul>
 */
public class GetViralExecutor extends HarnessExecutor {

    public static final String ORCHESTRATOR = "Showrunner";
    public static final String CRITIC = "ViralityCritic";

    private final StudioRun run;
    private final PromptBook promptBook;
    private final int maxRevisions;
    private final AtomicInteger criticRounds = new AtomicInteger();
    private final Map<String, List<AgentResult>> results = new ConcurrentHashMap<>();
    private volatile io.github.llm4j.getviral.tools.QualityGateTool qualityGate;
    private final AtomicInteger reviewRounds = new AtomicInteger();
    private volatile OriginalityGate originality;
    private final double creativity;

    /** Roles declared at or above this temperature in the .loom are "creative" and follow GETVIRAL_CREATIVITY. */
    static final double CREATIVE_FROM = 0.9;

    /**
     * Temperatures are declared per agent in getviral.loom ({@code temperature:}); Loom applies them.
     * {@code GETVIRAL_CREATIVITY} (default 1.0) scales only the creative roles, so the researcher,
     * critic and inspector stay precise however adventurous the writing gets.
     */
    static Double temperatureFor(Double declared, double creativity) {
        if (declared == null) return null;
        double t = declared >= CREATIVE_FROM ? declared * creativity : declared;
        return Math.max(0.0, Math.min(1.5, t));
    }

    public GetViralExecutor(LoomScript script, ToolRegistry tools, LLMClientFactory models,
                            StudioRun run, PromptBook promptBook, int maxRevisions) {
        super(script, tools, models);
        String knob = System.getenv("GETVIRAL_CREATIVITY");
        double c = 1.0;
        try {
            if (knob != null && !knob.isBlank()) c = Double.parseDouble(knob.strip());
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        this.creativity = Math.max(0.3, Math.min(1.5, c));
        this.run = run;
        this.promptBook = promptBook;
        this.maxRevisions = maxRevisions;
    }

    /** Every AgentResult produced in this run, per agent — what the eval4j suite asserts on. */
    public Map<String, List<AgentResult>> results() {
        return results;
    }

    /** The quality gate the Showrunner's build reviews are held to. */
    public void qualityGate(io.github.llm4j.getviral.tools.QualityGateTool gate) {
        this.qualityGate = gate;
    }

    public int reviewRounds() {
        return reviewRounds.get();
    }

    /** Checks castings and YouTube packages against this creator's past work (null = no check). */
    public void originality(OriginalityGate gate) {
        this.originality = gate;
    }

    public int criticRounds() {
        return criticRounds.get();
    }

    @Override
    protected void customizeAgent(AgentDef agentDef, ReActAgent.Builder builder) {
        String agent = agentDef.getName();
        Double temperature = temperatureFor(agentDef.getTemperature(), creativity);
        if (temperature != null) builder.temperature(temperature);
        // The ArtDirector makes five images, one tool call each — give it room.
        builder.maxIterations(agent.equals("ArtDirector") || agent.equals("VideoEditor") ? 12 : 8)
               .approvalCallback((tool, args, thought) -> {
                   // An approval is a question at this step: answered now, recorded, or the run suspends.
                   String questionId = currentStep() + "#approve:" + tool;
                   var recorded = getJournal().get(questionId);
                   if (recorded.isPresent()) return "approve".equals(String.valueOf(recorded.get().value()));
                   return run.approve(tool, args, thought, questionId);
               })
               .addListener(new AgentEventListener() {
                   @Override
                   public void onThought(String thought) {
                       run.emit("thought", Map.of("agent", agent, "text", thought));
                   }

                   @Override
                   public void onAction(String tool, String input) {
                       run.emit("action", Map.of("agent", agent, "tool", tool, "input", input == null ? "" : input));
                   }

                   @Override
                   public void onObservation(String observation) {
                       run.emit("observation", Map.of("agent", agent, "text", clip(observation, 1200)));
                   }

                   @Override
                   public void onApprovalRequired(String tool, Map<String, Object> args, String thought) {
                       run.emit("approval_needed", Map.of("agent", agent, "tool", tool));
                   }
               });
    }

    @Override
    protected ReActAgent agentForDelegate(DelegateStmt stmt, AgentDef agentDef, ReActAgent agent) {
        return promptBook.current(agentDef.getName())
                .map(version -> {
                    String framed = PromptBook.frame(agentDef.getName(), version.prompt());
                    run.emit("prompt_injected", Map.of("agent", agentDef.getName(), "version", version.version()));
                    boolean usesTools = !agentDef.getTools().isEmpty() || !agentDef.getMcpServers().isEmpty();
                    ReActAgent.Builder rebuilt = agent.toBuilder().systemPrompt(null);
                    return usesTools
                            ? rebuilt.instructions(framed).build()
                            : rebuilt.systemPrompt(framed).build();
                })
                .orElse(agent);
    }

    @Override
    protected String beforeDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload,
                                             String assembledContext) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", agentDef.getName());
        data.put("variable", stmt.getVariableName());
        data.put("task", clip(resolvedPayload, 600));
        if (CRITIC.equals(agentDef.getName())) data.put("round", criticRounds.get() + 1);
        if (ORCHESTRATOR.equals(agentDef.getName()) && resolvedPayload.startsWith("REVIEW THE BUILD")) {
            reviewStartedAt = System.nanoTime();
            data.put("review", reviewRounds.get() + 1);
        }
        run.emit("agent_start", data);
        return assembledContext;
    }

    @Override
    protected void afterDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload,
                                          String effectiveContext, AgentResult result, Object finalValue,
                                          DelegateSuccessHandler successHandler) {
        String agent = agentDef.getName();
        results.computeIfAbsent(agent, k -> new CopyOnWriteArrayList<>()).add(result);
        Object value = finalValue;

        boolean buildReview = ORCHESTRATOR.equals(agent) && resolvedPayload.startsWith("REVIEW THE BUILD");
        if (buildReview) {
            value = holdToTheGate(value);
        } else if (ORCHESTRATOR.equals(agent)) {
            boolean forOriginality = resolvedPayload.startsWith("RECAST FOR ORIGINALITY");
            boolean recast = resolvedPayload.startsWith("RECAST");
            promptBook.apply(value, forOriginality ? "Re-cast for originality" : recast ? "Re-cast after critic feedback" : "Cast for this brief");
            // Fresh castings (not the critic's partial re-casts) are checked against past castings.
            if ((!recast || forOriginality) && originality != null && value instanceof Map<?, ?> sheet) {
                Map<String, Object> verdict = originality.casting(sheet);
                Map<String, Object> annotated = new LinkedHashMap<>(castMap(sheet));
                String novelty = String.valueOf(verdict.get("novelty"));
                // One originality re-cast is allowed (see the .loom); after that, ship it and say so.
                annotated.put("novelty", forOriginality && novelty.equals("REPEAT") ? "REPEAT_ACCEPTED" : novelty);
                annotated.put("novelty_feedback", verdict.get("feedback"));
                value = annotated;
                emitOriginality("casting", forOriginality, verdict);
            }
        }

        if ("YouTubeProducer".equals(agent) && originality != null && value instanceof Map<?, ?> yt) {
            Map<String, Object> verdict = originality.youtube(yt);
            Map<String, Object> annotated = new LinkedHashMap<>(castMap(yt));
            if ("REPEAT".equals(verdict.get("novelty"))) {
                // Travels with the package, so the critic and the next revision see exactly what repeats.
                annotated.put("originality", "REPEAT — " + verdict.get("feedback"));
            } else {
                annotated.remove("originality");
            }
            value = annotated;
            emitOriginality("youtube", resolvedPayload.startsWith("REVISE"), verdict);
        }

        if (CRITIC.equals(agent)) {
            value = enforceRevisionBudget(value);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", agent);
        data.put("variable", stmt.getVariableName());
        data.put("value", value);
        data.put("iterations", result.getIterations());
        data.put("completed", result.isCompleted());
        data.put("tools", result.getSteps().stream().map(AgentResult.AgentStep::getAction).toList());
        if (result.getConfidence() != null) data.put("confidence", result.getConfidence().getScore());
        run.emit("agent_done", data);

        successHandler.onSuccess(result, value);

    }

    @Override
    protected void onDelegateError(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload,
                                   String effectiveContext, Exception error, int attempt, int maxAttempts) {
        run.emit("agent_error", Map.of("agent", agentDef.getName(), "error", String.valueOf(error.getMessage()),
                "attempt", attempt, "maxAttempts", maxAttempts));
    }

    /**
     * The Showrunner decides what to send back, but it can't wave a build through: the gate is re-read
     * (or run, if the Showrunner didn't call it), any area the gate fails is FIX whatever the Showrunner
     * said, and the verdict is COMPLETE only when every area passes. The Showrunner may still ask for
     * more work on an area the gate passed — its judgement adds to the gate, never subtracts.
     */
    @SuppressWarnings("unchecked")
    private Object holdToTheGate(Object value) {
        int round = reviewRounds.incrementAndGet();
        Map<String, Object> report = value instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?>)) report.put("summary", clip(String.valueOf(value), 300));
        if (qualityGate == null) return report;
        var result = qualityGate.last();
        if (result == null || result.at() < reviewStartedAt) result = qualityGate.run();
        boolean complete = true;
        for (String area : io.github.llm4j.getviral.quality.BuildReview.AREAS) {
            var gateArea = result.areas().get(area);
            boolean showrunnerWantsFix = "FIX".equalsIgnoreCase(String.valueOf(report.get(area)));
            boolean fix = !gateArea.pass() || showrunnerWantsFix;
            List<String> reasons = new java.util.ArrayList<>(gateArea.problems());
            String note = String.valueOf(report.getOrDefault(area + "_fix", "")).strip();
            if (!note.isEmpty() && !note.equals("null") && fix) reasons.add(0, note);
            report.put(area, fix ? "FIX" : "PASS");
            report.put(area + "_fix", fix ? String.join("; ", reasons) : "");
            complete &= !fix;
        }
        // A re-rendered video always follows fixes to the Reel plan or the images.
        if ("FIX".equals(report.get("reel")) || "FIX".equals(report.get("visuals"))) {
            report.put("video", "FIX");
            if (String.valueOf(report.get("video_fix")).isBlank()) report.put("video_fix", "re-render after the upstream fixes");
        }
        report.put("verdict", complete ? "COMPLETE" : "INCOMPLETE");
        report.put("round", round);
        // What the .loom's `for each fix in qualityReport.fixes` sends out — video last, since it is cut
        // from the Reel plan and the images.
        List<Map<String, Object>> fixes = new java.util.ArrayList<>();
        for (String area : io.github.llm4j.getviral.quality.BuildReview.AREAS) {
            if ("FIX".equals(report.get(area))) fixes.add(fix(area, String.valueOf(report.get(area + "_fix"))));
        }
        report.put("fixes", fixes);
        report.put("badges", result.badges().stream().map(io.github.llm4j.getviral.quality.QualityGate.Badge::toMap).toList());
        return report;
    }

    private volatile long reviewStartedAt;

    private static final Map<String, String[]> FIX_ROUTES = Map.of(
            "x", new String[] {"XWriter", "xPack", "REVISE the X package so it passes the quality gate."},
            "reel", new String[] {"ReelDirector", "reelPack", "REVISE the Reel so it passes the quality gate."},
            "youtube", new String[] {"YouTubeProducer", "youtubePack", "REVISE the YouTube package so it passes the quality gate."},
            "visuals", new String[] {"ArtDirector", "visualPack", "REGENERATE the images that failed the quality gate."},
            "video", new String[] {"VideoEditor", "videoPack", "RE-RENDER the Reel so it passes the quality gate."});

    /** One routed fix: who does it, where the result goes, what to do, and their current work. */
    private Map<String, Object> fix(String area, String problem) {
        String[] route = FIX_ROUTES.get(area);
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("area", area);
        fix.put("owner", route[0]);
        fix.put("output", route[1]);
        fix.put("task", route[2]);
        fix.put("problem", problem);
        fix.put("current", String.valueOf(getContext().getAll().getOrDefault(route[1], "")));
        return fix;
    }

    /** A resumed run replays recorded steps; restore what this executor tracks about them. */
    @Override
    protected void onDelegateReplayed(DelegateStmt stmt, AgentDef agentDef, Object value) {
        String agent = agentDef.getName();
        if (ORCHESTRATOR.equals(agent)) promptBook.restore(value);
        if (value instanceof Map<?, ?> report && report.get("round") instanceof Number round) {
            if (CRITIC.equals(agent)) criticRounds.set(round.intValue());
            if (ORCHESTRATOR.equals(agent) && "COMPLETE,INCOMPLETE".contains(String.valueOf(report.get("verdict")))) {
                reviewRounds.set(round.intValue());
            }
        }
    }

    private void emitOriginality(String stage, boolean retry, Map<String, Object> verdict) {
        Map<String, Object> data = new LinkedHashMap<>(verdict);
        data.put("stage", stage);
        data.put("retry", retry);
        run.emit("originality", data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * The critic loop is neural (the critic decides), but its budget is symbolic: after
     * {@code maxRevisions} revision rounds the pack ships regardless, with an honest note.
     */
    @SuppressWarnings("unchecked")
    private Object enforceRevisionBudget(Object value) {
        int round = criticRounds.incrementAndGet();
        Map<String, Object> report;
        if (value instanceof Map<?, ?> map) {
            report = new LinkedHashMap<>((Map<String, Object>) map);
        } else {
            report = new LinkedHashMap<>();
            report.put("verdict", "SHIP");
            report.put("headline", clip(String.valueOf(value), 200));
        }
        report.put("round", round);
        String verdict = String.valueOf(report.get("verdict")).trim().toUpperCase();
        if (!verdict.equals("SHIP") && round > maxRevisions) {
            report.put("verdict", "SHIP");
            report.put("budget_note", "Revision budget of " + maxRevisions
                    + " rounds reached — shipping the strongest version so far.");
        } else if (!verdict.equals("SHIP") && !verdict.equals("REVISE")) {
            report.put("verdict", "REVISE");
        } else {
            report.put("verdict", verdict);
        }
        return report;
    }

    static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
