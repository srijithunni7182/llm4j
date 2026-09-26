package io.github.llm4j.getviral.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.getviral.config.GetViralConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstagramPublishToolTest {

    private final InstagramPublishTool tool =
            new InstagramPublishTool(new InstagramGraphClient(GetViralConfig.from(Map.of())), null);

    @Test
    void alwaysRequiresHumanApproval() {
        assertThat(tool.requiresApproval(Map.of())).isTrue();
    }

    @Test
    void validatesAgainstInstagramLimitsBeforeCallingTheApi() {
        String caption = "#tag ".repeat(31);
        assertThat(InstagramPublishTool.validate("REELS", "http://insecure", caption, ""))
                .anyMatch(p -> p.contains("https"))
                .anyMatch(p -> p.contains("30 hashtags"));
        assertThat(InstagramPublishTool.validate("CAROUSEL", "https://x/y.mp4", "ok", ""))
                .anyMatch(p -> p.contains("media_type"));
        assertThat(InstagramPublishTool.validate("REELS", "https://x/y.mp4", "ok #fine", "")).isEmpty();
    }

    @Test
    void withoutCredentialsItDescribesTheExactGraphApiCallsInsteadOfPosting() throws Exception {
        String result = tool.execute(Map.of("media_type", "REELS", "media_url", "https://cdn.example.com/a.mp4",
                "caption", "Hello #GetViral"));
        assertThat(result).contains("DRY_RUN").contains("/media").contains("media_publish").contains("video_url");
    }
}
