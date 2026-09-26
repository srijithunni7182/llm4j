package io.github.llm4j.getviral.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.getviral.media.MediaInspector.Check;
import io.github.llm4j.getviral.media.MediaInspector.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InspectArtifactsToolTest {

    @Test
    void xLengthCountsLikeX() {
        assertThat(InspectArtifactsTool.xLength("hello")).isEqualTo(5);
        assertThat(InspectArtifactsTool.xLength("see https://example.com/a/very/long/path/that/is/long")).isEqualTo(4 + 23);
        assertThat(InspectArtifactsTool.xLength("🔥")).isEqualTo(2);
    }

    @Test
    void overLongPostsAndCaptionsFail() {
        Map<String, Object> vars = Map.of(
                "xPack", Map.of("thread", List.of("fine", "x".repeat(281)), "standalone", "ok", "reply_bait", "ok"),
                "reelPack", Map.of("caption", "c".repeat(2300), "hashtags", List.of("#a"), "beats", List.of(Map.of())),
                "youtubePack", Map.of("titles", List.of("t".repeat(120)), "description", "d", "tags", List.of("a")));
        List<Check> checks = InspectArtifactsTool.text(vars);
        assertThat(checks).filteredOn(c -> c.status() == Status.FAIL).extracting(Check::artifact)
                .containsExactlyInAnyOrder("x thread", "reel caption", "youtube package");
        assertThat(checks).anySatisfy(c -> assertThat(c.detail()).contains("post 2 is 281"));
    }
}
