package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.state.RunRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R38, R41: the security rules can be set centrally, in the page's form and as text; an acceptance needs a reason and is audited. */
class FabricConfigSecurityRulesTest {

    @TempDir
    Path dir;

    private FabricConfig config() {
        return new FabricConfig(dir, new RunRegistry(), new PrintStream(new ByteArrayOutputStream()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rules(FabricConfig c) {
        return (Map<String, Object>) ((Map<String, Object>) c.view().get("rules")).get("structured");
    }

    @Test
    void theDefaultsShownToThePageIncludeTheSecurityRules() {
        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) ((Map<String, Object>) config().view().get("rules")).get("defaults");
        assertThat(defaults).containsKeys("access_denied", "injection_scan");
        assertThat(((Map<?, ?>) defaults.get("access_denied")).get("count")).isEqualTo(3);
        assertThat(((Map<?, ?>) defaults.get("access_denied")).get("seconds")).isEqualTo(60);
        assertThat(defaults.get("injection_scan")).isEqualTo(true);
    }

    @Test
    void theStructuredFormCarriesTheSecurityRulesBothWays() {
        FabricConfig c = config();
        var result = c.saveStructured("rules", "{\"Support\":{\"access_denied\":{\"count\":5,\"seconds\":120},\"injection_scan\":false,"
                + "\"trifecta_accepted\":\"customer support: reads tickets, replies\"}}", "admin");
        assertThat(result.saved()).as(result.toString()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) rules(c).get("Support");
        assertThat(((Map<?, ?>) r.get("access_denied")).get("count")).isEqualTo(5);
        assertThat(((Map<?, ?>) r.get("access_denied")).get("seconds")).isEqualTo(120);
        assertThat(r.get("injection_scan")).isEqualTo(false);
        assertThat(r.get("trifecta_accepted")).isEqualTo("customer support: reads tickets, replies");
        @SuppressWarnings("unchecked")
        String text = (String) ((Map<String, Object>) c.view().get("rules")).get("text");
        assertThat(text).contains("access_denied: 5 in 2m").contains("injection_scan: off").contains("trifecta: accepted \"customer support: reads tickets, replies\"");
    }

    @Test
    void anAcceptanceWithoutAReasonIsRefused() {
        FabricConfig c = config();
        assertThat(c.saveStructured("rules", "{\"Support\":{\"trifecta_accepted\":\"\"}}", "admin").saved()).isFalse();
        assertThat(c.saveStructured("rules", "{\"Support\":{\"trifecta_accepted\":\"  \"}}", "admin").saved()).isFalse();
    }

    @Test
    void aTextSaveIsAppliedAndKept() {
        FabricConfig c = config();
        var result = c.save("rules", "Support { trifecta: accepted \"reviewed on 2026-10-14\"; access_denied: 4 in 30s }", "admin");
        assertThat(result.saved()).as(result.toString()).isTrue();
        FabricConfig again = config();
        again.load();
        assertThat(rules(again)).containsKey("Support"); // a second instance reads what the first kept
    }
}
