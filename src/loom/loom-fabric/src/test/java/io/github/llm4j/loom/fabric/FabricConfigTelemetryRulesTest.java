package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.state.RunRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R10, R13 at the server: the new rules can be set centrally, in the page's structured form and as
 * text, and old rules still load.
 */
class FabricConfigTelemetryRulesTest {

    @TempDir Path dir;

    private final RunRegistry registry = new RunRegistry();

    private FabricConfig config() {
        return new FabricConfig(dir, registry, new PrintStream(new ByteArrayOutputStream()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rules(Map<String, Map<String, Object>> view) {
        return view.get("rules");
    }

    @Test
    void theDefaultsShownToThePageIncludeTheNewRules() {
        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) rules(config().view()).get("defaults");
        assertThat(defaults)
                .containsKeys(
                        "context_nearly_full",
                        "truncated_answers",
                        "compaction_thrash",
                        "tool_success_rate",
                        "latency_spike");
        assertThat(((Map<?, ?>) defaults.get("context_nearly_full")).get("warn")).isEqualTo(85);
        assertThat(((Map<?, ?>) defaults.get("tool_success_rate")).get("below")).isEqualTo(70);
    }

    @Test
    void theStructuredFormCarriesTheNewRulesBothWays() {
        FabricConfig c = config();
        String json =
                "{\"Researcher\":{\"same_action\":3,\"context_nearly_full\":{\"warn\":80,\"high\":92},\"truncated_answers\":2,"
                        + "\"compaction_thrash\":{\"count\":5,\"of_calls\":12},\"tool_success_rate\":{\"below\":60,\"over_calls\":20},\"latency_spike\":3}}";
        var result = c.saveStructured("rules", json, "admin");
        assertThat(result.saved()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) rules(c.view()).get("structured");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) structured.get("Researcher");
        assertThat(r.get("same_action")).isEqualTo(3);
        assertThat(((Map<?, ?>) r.get("context_nearly_full")).get("high")).isEqualTo(92);
        assertThat(r.get("truncated_answers")).isEqualTo(2);
        assertThat(((Map<?, ?>) r.get("compaction_thrash")).get("of_calls")).isEqualTo(12);
        assertThat(((Map<?, ?>) r.get("tool_success_rate")).get("over_calls")).isEqualTo(20);
        assertThat(r.get("latency_spike")).isEqualTo(3.0);
        assertThat((String) rules(c.view()).get("text"))
                .contains("context_nearly_full: warn at 80%, high at 92%")
                .contains("latency_spike: 3x p50");
    }

    @Test
    void aTextSaveOfTheNewRulesIsAppliedAndKept() {
        FabricConfig c = config();
        var result = c.save("rules", "* { tool_success_rate: below 50% over 10 calls }\n", "admin");
        assertThat(result.saved()).isTrue();
        FabricConfig again = config();
        again.load();
        assertThat((String) rules(again.view()).get("text"))
                .contains("tool_success_rate: below 50% over 10 calls");
    }

    @Test
    void aBadNewRuleIsRefusedLikeAnyOther() {
        var result =
                config().save(
                                "rules",
                                "* { context_nearly_full: warn at 95%, high at 90% }\n",
                                "admin");
        assertThat(result.saved()).isFalse();
    }

    @Test
    void oldRulesFilesAndOldStructuredFormsStillWork() {
        FabricConfig c = config();
        assertThat(
                        c.saveStructured(
                                        "rules",
                                        "{\"Editor\":{\"same_action\":4,\"ungrounded_figures\":{\"warn\":2,\"high\":4}}}",
                                        "admin")
                                .saved())
                .isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) rules(c.view()).get("structured");
        Map<?, ?> editor = (Map<?, ?>) structured.get("Editor");
        assertThat(editor.containsKey("context_nearly_full")).isFalse();
        assertThat(editor.containsKey("latency_spike")).isFalse();
    }
}
