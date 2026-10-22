package io.github.llm4j.eval.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The scrubber behind every recorded fixture: no key, account id or request id survives it. */
class ScrubTest {

    @Test
    void theKeyAndKeyShapedStringsAreRemoved() {
        String key = "sk-proj-abcdefghijklmnopqrstuvwx";
        String out =
                Scrub.body(
                        "{\"echo\":\""
                                + key
                                + "\",\"auth\":\"Bearer abcdefghijklmnop1234\",\"x\":\"xai-AbCdEfGhIjKlMnOpQrStUv\"}",
                        List.of(key));
        assertThat(out)
                .doesNotContain(key)
                .doesNotContain("abcdefghijklmnop1234")
                .doesNotContain("AbCdEfGhIjKlMnOpQrStUv")
                .contains("SCRUBBED-KEY");
    }

    @Test
    void idsFingerprintsAndAccountFieldsAreReplacedByFixedText() {
        String in =
                "{\"id\":\"chatcmpl-9AbCdEfGh1234\",\"system_fingerprint\":\"fp_44709d6fcb\",\"organization\":\"org-Zq81dd7fNEXAMPLE\","
                        + "\"response_id\":\"resp_081e7a0a-4087-403b-8e74-a997d28d6fd2\",\"call\":\"call_Ku9yfMSIZWrJBGm2wqCaFF0G\",\"tool\":\"toolu_01A09q90qw90lq917835lq9\","
                        + "\"model\":\"gpt-6-luna\",\"text\":\"Paris is the capital of France.\",\"created\":1760000000,\"n\":12}";
        String out = Scrub.body(in, List.of());
        assertThat(out)
                .doesNotContain("9AbCdEfGh1234")
                .doesNotContain("44709d6fcb")
                .doesNotContain("Zq81dd7f")
                .doesNotContain("081e7a0a")
                .doesNotContain("Ku9yfMSIZW")
                .doesNotContain("01A09q90qw");
        assertThat(out)
                .contains("\"model\":\"gpt-6-luna\"")
                .contains("Paris is the capital of France.")
                .contains("\"created\":1760000000")
                .contains("\"n\":12");
        assertThat(Scrub.body(in, List.of())).isEqualTo(out); // deterministic
    }
}
