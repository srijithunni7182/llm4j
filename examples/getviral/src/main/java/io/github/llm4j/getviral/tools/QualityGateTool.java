package io.github.llm4j.getviral.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.getviral.media.MediaInspector;
import io.github.llm4j.getviral.quality.ArtifactChecks;
import io.github.llm4j.getviral.quality.BuildReview;
import io.github.llm4j.getviral.quality.QualityGate;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Showrunner's quality gate: inspects every file, checks platform limits and originality, and
 * runs the eval4j judges on the current X, Reel and YouTube packages — then says, per specialist,
 * what still has to be fixed. The last result is kept so the executor can hold the Showrunner to it.
 */
public class QualityGateTool implements Tool {

    public record Result(Map<String, BuildReview.Area> areas, List<QualityGate.Badge> badges,
                         List<MediaInspector.Check> inspection, long at) {
        public boolean complete() {
            return BuildReview.complete(areas);
        }
    }

    private final ArtifactChecks inspector;
    private final QualityGate gate;
    private final Supplier<Map<String, Object>> workflow;
    private final StudioEvents events;
    private volatile Result last;
    private int rounds;

    public QualityGateTool(ArtifactChecks inspector, QualityGate gate, Supplier<Map<String, Object>> workflow,
                           StudioEvents events) {
        this.inspector = inspector;
        this.gate = gate;
        this.workflow = workflow;
        this.events = events != null ? events : StudioEvents.NONE;
    }

    @Override
    public String getName() {
        return "quality_gate";
    }

    @Override
    public String getDescription() {
        return "Runs GetViral's quality gate on the whole build: every image and the Reel video decoded and "
                + "spec-checked, every post checked against its platform's limits and for originality, and the "
                + "eval4j judges scoring the X thread, Reel and YouTube package. Returns PASS or FIX per area "
                + "(x, reel, youtube, visuals, video) with the reasons. Args: {}.";
    }

    @Override
    public String execute(Map<String, Object> args) {
        return BuildReview.describe(run().areas());
    }

    /** Runs the full gate now. */
    public synchronized Result run() {
        Map<String, Object> vars = workflow.get();
        List<MediaInspector.Check> inspection = inspector.inspect();
        Map<String, Object> platforms = new LinkedHashMap<>();
        platforms.put("x", vars.get("xPack"));
        platforms.put("reel", vars.get("reelPack"));
        platforms.put("youtube", vars.get("youtubePack"));
        List<QualityGate.Badge> badges = gate.evaluate(platforms, String.valueOf(vars.get("hookChoice")), grounding(vars));
        Map<String, BuildReview.Area> areas = BuildReview.review(vars, inspection, badges);
        last = new Result(areas, badges, inspection, System.nanoTime());
        rounds++;

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("round", rounds);
        event.put("complete", last.complete());
        Map<String, Object> byArea = new LinkedHashMap<>();
        areas.forEach((k, a) -> byArea.put(k, Map.of("pass", a.pass(), "problems", a.problems())));
        event.put("areas", byArea);
        event.put("checks", inspection.stream().map(MediaInspector.Check::toMap).toList());
        events.emit("build_review", event);
        return last;
    }

    public Result last() {
        return last;
    }

    private static List<String> grounding(Map<String, Object> vars) {
        List<String> context = new ArrayList<>();
        if (vars.get("trendReport") != null) context.add(vars.get("trendReport").toString());
        if (vars.get("researchDossier") != null) context.add(vars.get("researchDossier").toString());
        if (vars.get("gamePlan") instanceof Map<?, ?> plan && plan.get("key_facts") instanceof List<?> facts) {
            context.add(String.join("\n", facts.stream().map(String::valueOf).toList()));
        }
        return context;
    }
}
