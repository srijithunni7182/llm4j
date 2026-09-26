package io.github.llm4j.getviral.quality;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.eval.judge.LlmJudgeCondition;
import io.github.llm4j.eval.judge.LlmJudgePresets;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * eval4j, used at runtime: every finished pack is graded by LLM-as-judge conditions — the same
 * {@link LlmJudgeCondition}s the test suite asserts with — and the verdicts are shown in the studio
 * as quality badges. One definition of "good", in tests and in production.
 */
public class QualityGate {

    public record Badge(String name, String platform, double score, double threshold, boolean passed, String reason) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("platform", platform);
            map.put("score", score);
            map.put("threshold", threshold);
            map.put("passed", passed);
            map.put("reason", reason);
            return map;
        }
    }

    private final LLMClient judge;
    private final StudioEvents events;

    public QualityGate(LLMClient judge, StudioEvents events) {
        this.judge = judge;
        this.events = events != null ? events : StudioEvents.NONE;
    }

    /** The criteria, exposed so the eval4j test suite asserts exactly what the studio shows. */
    public static List<Check> checks(LLMClient judge, String hook, List<String> groundingContext) {
        LlmJudgePresets presets = LlmJudgePresets.using(judge);
        return List.of(
                new Check("x", llmJudged("Scroll-stopping X hook")
                        .criteria("The first post of the thread would make a busy reader stop scrolling: it is "
                                + "specific, creates curiosity or tension, stands alone, and fits in 280 characters.")
                        .input(hook).judge(judge).threshold(0.6).build()),
                new Check("reel", llmJudged("Reel is platform-native")
                        .criteria("The Reel plan hooks in the first 3 seconds with on-screen text, changes visuals every "
                                + "few seconds, stays under 60 seconds, and ends with a clear save/share call to action.")
                        .judge(judge).threshold(0.6).build()),
                new Check("youtube", llmJudged("YouTube packaging is click-worthy and honest")
                        .criteria("Titles promise a concrete outcome without misleading clickbait, the thumbnail text "
                                + "complements the title, and the description has chapters starting at 0:00.")
                        .judge(judge).threshold(0.6).build()),
                new Check("pack", presets.groundedness(groundingContext, 0.5)),
                new Check("pack", presets.toxicity(0.7)));
    }

    public record Check(String platform, LlmJudgeCondition condition) { }

    public List<Badge> evaluate(Map<String, Object> pack, String hook, List<String> groundingContext) {
        List<Check> checks = checks(judge, hook, groundingContext);
        ExecutorService pool = Executors.newFixedThreadPool(checks.size());
        try {
            List<CompletableFuture<Badge>> futures = new ArrayList<>();
            for (Check check : checks) {
                Object subject = check.platform().equals("pack") ? pack.toString() : String.valueOf(pack.get(check.platform()));
                futures.add(CompletableFuture.supplyAsync(() -> grade(check, subject), pool));
            }
            List<Badge> badges = futures.stream().map(CompletableFuture::join).toList();
            events.emit("quality", Map.of("badges", badges.stream().map(Badge::toMap).toList()));
            return badges;
        } finally {
            pool.shutdown();
        }
    }

    private Badge grade(Check check, Object subject) {
        LlmJudgeCondition condition = check.condition();
        try {
            JudgeVerdict verdict = condition.evaluate(subject);
            return new Badge(condition.getName(), check.platform(), verdict.score(), condition.getThreshold(),
                    verdict.score() >= condition.getThreshold(), verdict.reason());
        } catch (RuntimeException e) {
            return new Badge(condition.getName(), check.platform(), 0, condition.getThreshold(), false,
                    "Judge unavailable: " + e.getMessage());
        }
    }
}
