package io.github.llm4j.getviral;

import static io.github.llm4j.eval.assertions.AgentAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.studio.StudioRun;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** The visual stage: the ArtDirector generates a full image set and the VideoEditor renders a real MP4. */
@ExtendWith(EvalReportExtension.class)
class VisualsEvalTest {

    @TempDir
    static Path dataDir;

    static GetViralEngine.Outcome outcome;
    static StudioRun run;

    @BeforeAll
    static void run() {
        GetViralEngine engine = GetViralTestSupport.engine(dataDir);
        GetViralEngine.Brief brief = GetViralTestSupport.brief("the 5-minute desk reset before work", "visuals.creator");
        run = GetViralTestSupport.newRun(brief);
        run.autopilot(GetViralTestSupport.creator(0, null, false));
        outcome = engine.run(run, brief);
        assertThat(outcome.status()).isEqualTo(StudioRun.Status.DONE);
    }

    @Test
    void artDirectorGeneratesEveryVisualTheFormatsNeed() {
        AgentResult art = last("ArtDirector");
        assertThat(art).completedSuccessfully().usesTool("generate_image");
        assertThat(art.getSteps()).filteredOn(s -> s.getAction().equals("generate_image")).hasSize(5);

        assertThat(media("image")).extracting(m -> String.valueOf(m.get("purpose")))
                .containsExactlyInAnyOrder("youtube_thumbnail", "reel_cover", "broll_1", "broll_2", "x_card");
    }

    @Test
    void imagesAreRealFilesWithTheRequestedAspectRatios() throws Exception {
        for (Map<?, ?> image : media("image")) {
            Path file = fileOf(image);
            BufferedImage decoded = ImageIO.read(file.toFile());
            assertThat(decoded).as(file.toString()).isNotNull();
            boolean portrait = String.valueOf(image.get("purpose")).startsWith("reel") || String.valueOf(image.get("purpose")).startsWith("broll");
            assertThat(decoded.getHeight() > decoded.getWidth()).as(image.get("purpose") + " orientation").isEqualTo(portrait);
        }
    }

    @Test
    void videoEditorRendersAPlayableMp4() throws Exception {
        assertThat(last("VideoEditor")).completedSuccessfully().usesToolsInOrder("generate_video_clip", "render_reel");

        List<Map<?, ?>> videos = media("video").stream().filter(m -> "reel".equals(m.get("purpose"))).toList();
        assertThat(videos).hasSize(1);
        Path mp4 = fileOf(videos.get(0));
        byte[] head = java.util.Arrays.copyOf(Files.readAllBytes(mp4), 12);
        assertThat(new String(head, 4, 4)).as("MP4 'ftyp' box").isEqualTo("ftyp");
        assertThat(Files.size(mp4)).isGreaterThan(10_000);
        assertThat(((Number) videos.get(0).get("seconds")).doubleValue()).isBetween(10.0, 60.0);
    }

    @Test
    void theReelShipsAsAnInstagramReadyMp4PlusAWebmCopyForBrowsers() throws Exception {
        Map<?, ?> reel = media("video").stream().filter(m -> "reel".equals(m.get("purpose"))).findFirst().orElseThrow();
        Map<?, ?> webm = media("video").stream().filter(m -> "reel_webm".equals(m.get("purpose"))).findFirst().orElseThrow();
        Path mp4 = fileOf(reel);
        assertThat(String.valueOf(webm.get("url"))).isEqualTo(String.valueOf(reel.get("url")).replace(".mp4", "-preview.webm"));

        assertThat(io.github.llm4j.getviral.media.Mp4FastStart.isFastStart(mp4)).as("moov before mdat").isTrue();
        assertThat(io.github.llm4j.getviral.media.MediaInspector.reel(mp4))
                .allSatisfy(c -> assertThat(c.status()).as(c.detail()).isNotEqualTo(io.github.llm4j.getviral.media.MediaInspector.Status.FAIL))
                .anySatisfy(c -> assertThat(c.detail()).contains("start, middle and end frames decode"));
        double seconds = ((Number) reel.get("seconds")).doubleValue();
        assertThat(io.github.llm4j.getviral.media.MediaInspector.webm(fileOf(webm), seconds))
                .allSatisfy(c -> assertThat(c.status()).as(c.detail()).isEqualTo(io.github.llm4j.getviral.media.MediaInspector.Status.PASS));
    }

    @Test
    void theShowrunnerSignsOffEveryArtifactBeforeTheCreatorSeesIt() {
        Map<?, ?> build = (Map<?, ?>) outcome.pack().get("build");
        assertThat(build.get("verdict")).isEqualTo("COMPLETE");
        assertThat((List<?>) build.get("fixes")).isEmpty();
        Map<?, ?> review = run.events().stream().filter(e -> "build_review".equals(e.get("type")))
                .map(e -> (Map<?, ?>) e.get("data")).reduce((a, b) -> b).orElseThrow();
        assertThat(review.get("complete")).isEqualTo(true);
        assertThat((List<?>) review.get("checks")).extracting(c -> String.valueOf(((Map<?, ?>) c).get("artifact")))
                .contains("youtube_thumbnail", "reel_cover", "x_card", "broll_1", "broll_2", "reel.mp4",
                        "reel-preview.webm", "x thread", "reel caption", "youtube package");
    }

    @Test
    void offlineImagesAreHonestlyLabelledAsNotAi() {
        assertThat(media("image")).allSatisfy(m -> {
            assertThat(m.get("ai")).isEqualTo(false);
            assertThat(String.valueOf(m.get("provider"))).contains("Local design render");
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> media(String kind) {
        return ((List<Map<?, ?>>) outcome.pack().get("media")).stream().filter(m -> kind.equals(m.get("kind"))).toList();
    }

    private static Path fileOf(Map<?, ?> asset) {
        return dataDir.resolve(String.valueOf(asset.get("url")).substring(1));
    }

    private static AgentResult last(String agent) {
        List<AgentResult> results = outcome.executor().results().get(agent);
        assertThat(results).as(agent).isNotEmpty();
        return results.get(results.size() - 1);
    }
}
