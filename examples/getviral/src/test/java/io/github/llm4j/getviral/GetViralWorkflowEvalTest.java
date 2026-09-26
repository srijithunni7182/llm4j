package io.github.llm4j.getviral;

import static io.github.llm4j.eval.assertions.AgentAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.engine.PromptBook;
import io.github.llm4j.getviral.studio.StudioRun;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * eval4j over a whole Loom workflow run: trajectory (which tools each agent used, in what order),
 * structure (typed outputs per platform), orchestration (dynamic prompts, bounded critic loop) and
 * safety (the PII guardrail). Runs on the demo model by default; set GEMINI_API_KEY to evaluate the
 * same workflow against a real model.
 */
@ExtendWith(EvalReportExtension.class)
class GetViralWorkflowEvalTest {

    @TempDir
    static Path dataDir;

    static GetViralEngine.Outcome outcome;
    static StudioRun run;

    @BeforeAll
    static void runTheWorkflowOnce() {
        GetViralEngine engine = GetViralTestSupport.engine(dataDir);
        GetViralEngine.Brief brief = GetViralTestSupport.brief("how to beat procrastination as a student", "eval.creator");
        run = GetViralTestSupport.newRun(brief);
        run.autopilot(GetViralTestSupport.creator(1, null, false));
        outcome = engine.run(run, brief);
    }

    @Test
    void workflowShipsAPackForEveryPlatform() {
        assertThat(outcome.status()).isEqualTo(StudioRun.Status.DONE);
        assertThat(outcome.pack()).containsKeys("x", "reel", "youtube", "hook", "critic", "quality");
        assertThat(outcome.pack().get("x")).isInstanceOf(Map.class);
        assertThat(platform("x")).containsKey("thread");
        assertThat(platform("reel")).containsKeys("beats", "caption", "hashtags");
        assertThat(platform("youtube")).containsKeys("titles", "chapters", "thumbnail_text");
    }

    @Test
    void trendScoutResearchesLiveSignalsBeforeAnswering() {
        AgentResult scout = only("TrendScout");
        assertThat(scout)
                .completedSuccessfully()
                .usesToolsInOrder("trending_now", "hn_pulse", "trending_hashtags")
                .completesWithinIterations(8);
    }

    @Test
    void strategistGroundsHooksInThePlaybookViaRag() {
        assertThat(only("Strategist"))
                .completedSuccessfully()
                .usesTool("viral_playbook")
                .hasValidJson(Map.class);
    }

    @Test
    void creatorsHookChoiceFlowsIntoEveryPlatform() {
        String hook = String.valueOf(outcome.pack().get("hook"));
        assertThat(hook).isNotBlank();
        assertThat(String.valueOf(outcome.pack().get("x"))).contains(hook);
        assertThat(String.valueOf(outcome.pack().get("reel"))).contains(hook);
    }

    @Test
    void showrunnerWritesPromptsAtRuntimeAndRewritesThemAfterFeedback() {
        Map<String, List<PromptBook.Version>> prompts = outcome.prompts().all();
        assertThat(prompts).containsKeys("TrendScout", "Strategist", "XWriter", "ReelDirector", "YouTubeProducer",
                "ViralityCritic");
        // The critic asked for a revision, so the platform writers were re-cast with v2 prompts.
        assertThat(prompts.get("XWriter")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(run.events()).anySatisfy(e -> assertThat(e.get("type")).isEqualTo("prompt_injected"));
    }

    @Test
    void criticLoopIsBoundedAndEndsInShip() {
        Map<?, ?> critic = (Map<?, ?>) outcome.pack().get("critic");
        assertThat(critic.get("verdict")).isEqualTo("SHIP");
        assertThat(outcome.executor().criticRounds()).isBetween(1, 3);
    }

    @Test
    void qualityGateGradesTheFinishedPackWithEval4jJudges() {
        assertThat(outcome.badges()).hasSize(5);
        assertThat(outcome.badges()).allSatisfy(b -> assertThat(b.score()).isBetween(0.0, 1.0));
    }

    @Test
    void engramRemembersTheCreatorForNextTime() {
        assertThat(GetViralTestSupport.engine(dataDir).memories("eval.creator"))
                .anySatisfy(m -> assertThat(String.valueOf(m.get("content"))).contains("picked the hook"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> platform(String key) {
        return (Map<String, Object>) outcome.pack().get(key);
    }

    private static AgentResult only(String agent) {
        List<AgentResult> results = outcome.executor().results().get(agent);
        assertThat(results).as("results for " + agent).isNotEmpty();
        return results.get(results.size() - 1);
    }
}
