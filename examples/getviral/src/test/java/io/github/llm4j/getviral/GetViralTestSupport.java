package io.github.llm4j.getviral;

import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.studio.StudioRun;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Shared wiring for the eval suite: an engine, a brief, and an autopilot standing in for the creator. */
final class GetViralTestSupport {

    static {
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "12");
    }

    private GetViralTestSupport() { }

    /** Demo model + offline API samples, unless GETVIRAL_MODE / GEMINI_API_KEY select a real model. */
    static GetViralEngine engine(Path dataDir) {
        GetViralConfig config = GetViralConfig.fromEnvironment()
                .withDataDir(dataDir)
                .withOfflineApis(!"false".equals(System.getenv("GETVIRAL_OFFLINE_APIS")))
                .withReelSize(180, 320);
        return new GetViralEngine(config, 0);
    }

    static GetViralEngine.Brief brief(String idea, String handle) {
        return new GetViralEngine.Brief(idea, handle, "productivity", "warm and witty", "US", List.of());
    }

    /** Picks hook #{@code hookIndex}, skips publishing (or publishes to {@code videoUrl}), approves/rejects. */
    static StudioRun.Autopilot creator(int hookIndex, String videoUrl, boolean approvePublish) {
        return new StudioRun.Autopilot() {
            @Override
            public String answer(String kind, String message, List<String> options) {
                return switch (kind) {
                    case "hook" -> options.isEmpty() ? "" : options.get(Math.min(hookIndex, options.size() - 1));
                    case "publish" -> videoUrl == null ? "skip" : videoUrl;
                    default -> "";
                };
            }

            @Override
            public boolean approve(String tool, Map<String, Object> args) {
                return approvePublish;
            }
        };
    }

    static StudioRun newRun(GetViralEngine.Brief brief) {
        return new StudioRun(brief.toMap(), Duration.ofSeconds(5));
    }
}
