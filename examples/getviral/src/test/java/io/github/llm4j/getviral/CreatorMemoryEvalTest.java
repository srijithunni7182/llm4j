package io.github.llm4j.getviral;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.studio.StudioRun;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Engram makes GetViral better with every run: the second run is briefed by the first. */
@ExtendWith(EvalReportExtension.class)
class CreatorMemoryEvalTest {

    @TempDir
    Path dataDir;

    @Test
    void secondRunIsBriefedWithWhatTheFirstRunLearned() {
        GetViralEngine engine = GetViralTestSupport.engine(dataDir);

        StudioRun first = run(engine, "why walking meetings beat Zoom calls");
        assertThat(recalled(first, "Showrunner")).as("first run starts with no memory").isFalse();

        engine.feedback("memory.creator", "reel", true, "the loopable ending");

        StudioRun second = run(engine, "walking meetings for remote teams");
        assertThat(recalled(second, "Showrunner")).as("second run is briefed by Engram").isTrue();
        assertThat(String.valueOf(pack(second).get("casting"))).contains("Building on what worked before");
    }

    @Test
    void newerFeedbackShadowsOlderFeedbackOnTheSameTopic() {
        GetViralEngine engine = GetViralTestSupport.engine(dataDir);
        engine.feedback("shadow.creator", "x", true, "punchy thread");
        engine.feedback("shadow.creator", "x", false, "too long");

        List<Map<String, Object>> memories = engine.memories("shadow.creator");
        assertThat(memories).filteredOn(m -> Boolean.FALSE.equals(m.get("shadow")))
                .extracting(m -> String.valueOf(m.get("content")))
                .containsExactly("The creator did NOT like the x output (too long) — try a different approach next time.");
    }

    private static StudioRun run(GetViralEngine engine, String idea) {
        GetViralEngine.Brief brief = GetViralTestSupport.brief(idea, "memory.creator");
        StudioRun run = GetViralTestSupport.newRun(brief);
        run.autopilot(GetViralTestSupport.creator(0, null, false));
        engine.run(run, brief);
        return run;
    }

    private static boolean recalled(StudioRun run, String agent) {
        return run.events().stream()
                .filter(e -> "memory_recall".equals(e.get("type")))
                .map(e -> (Map<?, ?>) e.get("data"))
                .filter(d -> agent.equals(d.get("agent")))
                .findFirst()
                .map(d -> Boolean.TRUE.equals(d.get("recalled")))
                .orElse(false);
    }

    private static Map<?, ?> pack(StudioRun run) {
        return run.events().stream().filter(e -> "pack".equals(e.get("type")))
                .map(e -> (Map<?, ?>) e.get("data")).findFirst().orElseThrow();
    }
}
