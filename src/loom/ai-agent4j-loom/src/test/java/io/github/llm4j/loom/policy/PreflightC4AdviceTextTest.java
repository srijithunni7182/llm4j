package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** C4 no longer teaches the god prompt, and the guide's example is tone, not a rule. */
class PreflightC4AdviceTextTest {

    @Test
    void theAdviceSendsAMattersRuleToAPolicyNotToTheAgentsPrompt() throws Exception {
        String src =
                Files.readString(
                        Path.of(
                                "src/main/java/io/github/llm4j/loom/preflight/PreflightRunner.java"));
        assertThat(src).contains("a prompt is a wish, not a guarantee");
        assertThat(src).contains("put the rule in a policy and check replies against it");
        assertThat(src).doesNotContain("put the rule that matters in the agent's own prompt");
        assertThat(src).doesNotContain("move the rule that matters into the agent's own prompt");
    }

    @Test
    void theGuidesSharedPromptExampleIsToneAndLinksToPolicies() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));
        assertThat(guide).doesNotContain("Never promise a refund. Answer in plain English");
        assertThat(guide).contains("plain English, short sentences, no jargon");
        assertThat(guide).contains("#policies-rules-checked-at-the-edge");
    }
}
