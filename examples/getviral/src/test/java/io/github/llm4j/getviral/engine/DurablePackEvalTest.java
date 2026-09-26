package io.github.llm4j.getviral.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.studio.StudioRun;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pack with nobody at the keyboard: every question (the hook pick, the publish step, the publish
 * approval inside the Publisher) suspends the run without holding a thread, and each answer resumes
 * it from the journal — nothing already done is ever re-run.
 */
class DurablePackEvalTest {

    @TempDir
    Path dataDir;

    @Test
    void aPackSuspendsAtEveryQuestionAndResumesWithoutRepeatingWork() {
        GetViralConfig config = GetViralConfig.from(Map.of("GETVIRAL_MODE", "demo", "GETVIRAL_OFFLINE_APIS", "true",
                "GETVIRAL_IMAGE_PROVIDER", "local")).withDataDir(dataDir).withReelSize(180, 320);
        GetViralEngine engine = new GetViralEngine(config, 0);
        GetViralEngine.Brief brief = new GetViralEngine.Brief("a 5-minute desk reset", "tidy.tom", "productivity",
                "calm and wise", "US", List.of());
        StudioRun run = new StudioRun(brief.toMap()); // no autopilot: questions suspend
        RunJournal journal = new FileRunJournal(dataDir.resolve("journal.json"));

        // 1. Runs until the creator must pick a hook.
        GetViralEngine.Outcome first = engine.run(run, brief, GetViralEngine.Publishing.DRY_RUN, journal);
        assertThat(first.status()).isEqualTo(StudioRun.Status.WAITING_FOR_HUMAN);
        Map<?, ?> hookQuestion = lastQuestion(run);
        assertThat(hookQuestion.get("kind")).isEqualTo("hook");
        List<?> hooks = (List<?>) hookQuestion.get("options");
        assertThat(hooks).hasSize(5);
        int stepsBeforeHook = journal.all().size();

        // 2. The answer arrives (later, anywhere): the run resumes and stops at the publish question.
        journal.answer(String.valueOf(hookQuestion.get("id")), hooks.get(2));
        GetViralEngine.Outcome second = engine.run(run, brief, GetViralEngine.Publishing.DRY_RUN, journal);
        assertThat(second.status()).isEqualTo(StudioRun.Status.WAITING_FOR_HUMAN);
        assertThat(lastQuestion(run).get("kind")).isEqualTo("publish");
        assertThat(second.executor().results().get("Showrunner")).as("casting was replayed, not re-run")
                .allSatisfy(r -> assertThat(r).isNotNull())
                .hasSizeLessThanOrEqualTo(3); // only the build reviews ran in this part
        assertThat(second.executor().results()).doesNotContainKeys("Researcher", "TrendScout", "Strategist");
        assertThat(journal.all().size()).isGreaterThan(stepsBeforeHook);

        // 3. The creator wants it published: the Publisher asks for approval of the exact post — another pause.
        journal.answer(String.valueOf(lastQuestion(run).get("id")), "https://cdn.example.com/reel.mp4");
        GetViralEngine.Outcome third = engine.run(run, brief, GetViralEngine.Publishing.DRY_RUN, journal);
        assertThat(third.status()).isEqualTo(StudioRun.Status.WAITING_FOR_HUMAN);
        Map<?, ?> approval = lastQuestion(run);
        assertThat(approval.get("kind")).isEqualTo("approval");
        assertThat(String.valueOf(approval.get("id"))).contains("#approve:instagram_publish");
        assertThat(third.executor().results()).doesNotContainKeys("XWriter", "ArtDirector", "VideoEditor");

        // 4. Approved: the Publisher re-runs its step, sees the recorded approval and finishes.
        journal.answer(String.valueOf(approval.get("id")), "approve");
        GetViralEngine.Outcome done = engine.run(run, brief, GetViralEngine.Publishing.DRY_RUN, journal);
        assertThat(done.status()).isEqualTo(StudioRun.Status.DONE);
        assertThat(done.pack().get("hook")).isEqualTo(hooks.get(2));
        assertThat(String.valueOf(done.pack().get("publish"))).contains("DRY_RUN");
        assertThat((List<?>) done.pack().get("media")).as("media listed from before the pauses").isNotEmpty();
        assertThat(((Map<?, ?>) done.pack().get("build")).get("verdict")).isEqualTo("COMPLETE");
        assertThat(done.executor().results()).containsOnlyKeys("Publisher");
    }

    private static Map<?, ?> lastQuestion(StudioRun run) {
        return run.events().stream().filter(e -> "human".equals(e.get("type"))).map(e -> (Map<?, ?>) e.get("data"))
                .reduce((a, b) -> b).orElseThrow();
    }
}
