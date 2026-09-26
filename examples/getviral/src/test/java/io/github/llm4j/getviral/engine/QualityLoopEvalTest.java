package io.github.llm4j.getviral.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.studio.StudioRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Showrunner owns the finished build: it reviews every artifact against the quality gate and sends
 * failures back until X, Instagram and YouTube all pass. A pack is only DONE when they do.
 */
class QualityLoopEvalTest {

    @TempDir
    Path dataDir;

    private GetViralEngine engine() {
        GetViralConfig config = GetViralConfig.from(Map.of("GETVIRAL_MODE", "demo", "GETVIRAL_OFFLINE_APIS", "true",
                        "GETVIRAL_IMAGE_PROVIDER", "local"))
                .withDataDir(dataDir).withReelSize(180, 320);
        return new GetViralEngine(config, 0);
    }

    private StudioRun run(GetViralEngine.Brief brief) {
        StudioRun run = new StudioRun(brief.toMap());
        run.autopilot(new StudioRun.Autopilot() {
            @Override
            public String answer(String kind, String message, List<String> options) {
                return "hook".equals(kind) && !options.isEmpty() ? options.get(0) : "skip";
            }

            @Override
            public boolean approve(String tool, Map<String, Object> args) {
                return false;
            }
        });
        return run;
    }

    /** Wraps render_reel so the first {@code broken} renders come out truncated (unplayable). */
    private Tool brokenRenders(Tool real, int broken, AtomicInteger calls) {
        return new Tool() {
            public String getName() { return real.getName(); }
            public String getDescription() { return real.getDescription(); }
            public String execute(Map<String, Object> args) throws Exception {
                String out = real.execute(args);
                if (calls.incrementAndGet() <= broken) {
                    Matcher m = Pattern.compile("url: (/media/\\S+)").matcher(out);
                    if (m.find()) {
                        Path mp4 = dataDir.resolve(m.group(1).substring(1));
                        byte[] bytes = Files.readAllBytes(mp4);
                        Files.write(mp4, java.util.Arrays.copyOf(bytes, bytes.length / 3));
                    }
                }
                return out;
            }
        };
    }

    private static List<Map<?, ?>> reviews(StudioRun run) {
        return run.events().stream().filter(e -> "build_review".equals(e.get("type"))).<Map<?, ?>>map(e -> (Map<?, ?>) e.get("data")).toList();
    }

    @Test
    void theShowrunnerSendsABrokenReelBackUntilEveryArtifactPasses() {
        GetViralEngine engine = engine();
        AtomicInteger renders = new AtomicInteger();
        // The VideoEditor's render and the first re-render both come out broken; only a third render works.
        engine.decorateTool("RenderReel", real -> brokenRenders(real, 2, renders));
        GetViralEngine.Brief brief = new GetViralEngine.Brief("a 5-minute morning stretch", "flex.fox", "fitness", "calm and wise", "US", List.of());
        StudioRun run = run(brief);
        GetViralEngine.Outcome outcome = engine.run(run, brief);

        assertThat(outcome.status()).isEqualTo(StudioRun.Status.DONE);
        List<Map<?, ?>> reviews = reviews(run);
        assertThat(reviews).hasSizeGreaterThanOrEqualTo(2);
        assertThat(reviews.get(0).get("complete")).isEqualTo(false);
        assertThat(((Map<?, ?>) ((Map<?, ?>) reviews.get(0).get("areas")).get("video")).get("pass")).isEqualTo(false);
        assertThat(reviews.get(reviews.size() - 1).get("complete")).isEqualTo(true);
        assertThat(renders.get()).isGreaterThanOrEqualTo(3);

        Map<?, ?> build = (Map<?, ?>) outcome.pack().get("build");
        assertThat(build.get("verdict")).isEqualTo("COMPLETE");
        assertThat(outcome.executor().results().get("VideoEditor")).hasSizeGreaterThanOrEqualTo(2);
        // Publishing is only offered once the build is complete.
        assertThat(outcome.executor().getContext().getAll()).containsKey("reelVideoUrl");
    }

    @Test
    void aBuildThatNeverPassesIsNotDoneEvenIfTheShowrunnerIsToldItIs() {
        GetViralEngine engine = engine();
        engine.decorateTool("RenderReel", real -> brokenRenders(real, Integer.MAX_VALUE, new AtomicInteger()));
        // The gate really runs, but the Showrunner is shown a report claiming everything passed.
        engine.decorateTool("QualityGate", real -> new Tool() {
            public String getName() { return real.getName(); }
            public String getDescription() { return real.getDescription(); }
            public String execute(Map<String, Object> args) throws Exception {
                real.execute(args);
                return "BUILD COMPLETE — every artifact for X, Instagram and YouTube passes the quality gate.";
            }
        });
        GetViralEngine.Brief brief = new GetViralEngine.Brief("a 5-minute morning stretch", "flex.fox", "fitness", "calm and wise", "US", List.of());
        StudioRun run = run(brief);
        GetViralEngine.Outcome outcome = engine.run(run, brief);

        assertThat(outcome.status()).isEqualTo(StudioRun.Status.FAILED);
        assertThat(reviews(run)).hasSize(5).allSatisfy(r -> assertThat(r.get("complete")).isEqualTo(false));
        Map<?, ?> build = (Map<?, ?>) outcome.pack().get("build");
        assertThat(build.get("verdict")).as("the executor holds the Showrunner to the gate").isEqualTo("INCOMPLETE");
        assertThat(build.get("video")).isEqualTo("FIX");
        assertThat(run.events()).anySatisfy(e -> {
            assertThat(e.get("type")).isEqualTo("error");
            assertThat(String.valueOf(((Map<?, ?>) e.get("data")).get("message"))).contains("isn't marked done").contains("video");
        });
        assertThat(outcome.executor().getContext().getAll()).as("no publish offer for an incomplete build")
                .doesNotContainKey("reelVideoUrl");
    }
}
