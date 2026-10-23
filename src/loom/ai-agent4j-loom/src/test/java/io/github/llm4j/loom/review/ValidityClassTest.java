package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.adapt.ValidityCatalog;
import io.github.llm4j.loom.review.adapt.ValidityClass;
import io.github.llm4j.loom.review.engine.RuleInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R3, R19 of spec 09 (§6.2): the messages weave check really writes get stable rule ids. */
class ValidityClassTest {

    /** Messages as weave check writes them, taken from real runs. */
    static final Map<String, String> REAL =
            Map.ofEntries(
                    Map.entry("there is no agent named Nope Agents defined: A.", "LV01"),
                    Map.entry(
                            "tool Zed is not defined: declare it (tool Zed { use: ? }), use a built-in, map it in a .loot file, or register it from Java",
                            "LV01"),
                    Map.entry("mcp server x is not defined", "LV01"),
                    Map.entry("r is set here and never used", "LV03"),
                    Map.entry(
                            "the answer to a is never read, so the workflow does the same whatever the person says",
                            "LV05"),
                    Map.entry("declared twice", "LV07"),
                    Map.entry("prompt files: prompts/a.md does not exist", "LV02"),
                    Map.entry(
                            "url must come from the environment or the secret store, e.g. url: secret.T_URL (or env.T_URL)",
                            "LV02"),
                    Map.entry("secret API_KEY is not in the secret store", "LV02"),
                    Map.entry("model gemini-x needs GEMINI_API_KEY in the environment", "LV02"),
                    Map.entry("source docs does not exist (looked in /x)", "LV02"),
                    Map.entry("step has no field tier", "LV04"),
                    Map.entry("the answer is not JSON: it has no \"kind\"", "LV04"),
                    Map.entry("step cannot be reached from this step", "LV06"),
                    Map.entry(
                            "steerable: true without streaming: true; a steer waits for the model call in progress to finish",
                            "LV09"),
                    Map.entry("reasoning: true has no effect without streaming: true", "LV09"),
                    Map.entry("unknown option foo", "LV09"),
                    Map.entry("chunk_size must be positive", "LV09"),
                    Map.entry("needs primary: \"<model>\"", "LV09"));

    @Test
    void everyRealMessageGetsItsRuleNotTheUnclassifiedBucket() {
        REAL.forEach(
                (message, id) -> {
                    ValidityClass c = ValidityClass.of(message);
                    assertThat(c).as(message).isNotNull();
                    assertThat(c.id()).as(message).isEqualTo(id);
                });
    }

    @Test
    void aMessageNoClassClaimsIsLeftForTheUnclassifiedBucket() {
        assertThat(ValidityClass.of("something entirely different happened")).isNull();
    }

    @Test
    void everyClassIsExplainedAndHasAnId() {
        List<String> ids = ValidityCatalog.rules().stream().map(RuleInfo::id).toList();
        assertThat(ids)
                .contains("LV00", "LV01", "LV02", "LV03", "LV04", "LV05", "LV06", "LV07", "LV09");
        assertThat(ids).doesNotHaveDuplicates();
        for (ValidityClass c : ValidityClass.values()) {
            assertThat(c.title()).isNotBlank();
            assertThat(c.why()).isNotBlank();
            assertThat(c.fixHint()).isNotBlank();
        }
    }
}
