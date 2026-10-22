package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code weave audit} lists a classifier whose state leaves the machine as a data flow, like a
 * hosted model (DESIGN 5.5, acceptance A14, rule LA19).
 */
class ClassifierAuditTest {

    private static List<Finding> la19(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return SecurityAudit.audit(script, "w.loom").findings().stream()
                .filter(f -> f.rule().equals("LA19"))
                .toList();
    }

    private static String classifier(String model, String extra) {
        return "intentclassifier Intent { model: \""
                + model
                + "\" "
                + extra
                + " choose kind: refund | other confidence at least 0.8 }\n"
                + "workflow Triage(message, history) { intentclassify Intent on { message: message, history: history } -> intent\n note \"{intent.kind}\" }\n";
    }

    @Test
    void jevIsListedAsAHostedServiceWithTheFieldsItIsSent() {
        List<Finding> found = la19(classifier("Jev/jev-latest", ""));

        assertThat(found).hasSize(1);
        Finding f = found.get(0);
        assertThat(f.severity()).isEqualTo(Severity.INFO);
        assertThat(f.where()).isEqualTo("intentclassifier Intent");
        assertThat(f.title()).isEqualTo("A classifier sends its state to a hosted service");
        assertThat(f.risk())
                .contains("Intent")
                .contains("message, history")
                .contains("Jev")
                .contains("api.typesafe.ai")
                .contains("leaves this machine");
        assertThat(f.fix()).contains("pii: mask");
        assertThat(f.owasp()).contains(Owasp.LLM02);
        assertThat(f.line()).isEqualTo(1);
    }

    @Test
    void aChatModelIsListedWithItsProviderAndMarkedUncalibratedWhenItGatesOnConfidence() {
        Finding f = la19(classifier("gemini-2.5-flash", "")).get(0);

        assertThat(f.risk()).contains("Google").contains("uncalibrated");
        assertThat(la19(classifier("claude-haiku-5-5", "")).get(0).risk()).contains("Anthropic");
        assertThat(la19(classifier("sarvam/sarvam-m", "")).get(0).risk()).contains("Sarvam");
    }

    @Test
    void calibratedJevIsNotCalledUncalibrated() {
        assertThat(la19(classifier("Jev/jev-latest", "")).get(0).risk())
                .doesNotContain("uncalibrated");
    }

    @Test
    void aPiiSettingIsAcknowledgedInTheFix() {
        Finding f = la19(classifier("Jev/jev-latest", "pii: mask")).get(0);

        assertThat(f.risk()).contains("personal data is masked first");
        assertThat(f.fix()).doesNotContain("Add pii");
    }

    @Test
    void aBackendOnThisMachineIsNotAFlowOut() {
        assertThat(la19(classifier("ollama/llama3", ""))).isEmpty();
        assertThat(la19("provider Box { use: ollama }\n" + classifier("Box/llama3", ""))).isEmpty();
        assertThat(
                        la19(
                                "provider Local { use: jev  api_key: env.K  base_url: \"http://localhost:9000\" }\n"
                                        + classifier("Local/jev-latest", "")))
                .isEmpty();
    }

    @Test
    void aDeclaredProviderIsNamedByItsEndpoint() {
        Finding f =
                la19("provider Scorer { use: jev  api_key: env.K  base_url: \"https://jev.example.internal\" }\n"
                                + classifier("Scorer/jev-latest", ""))
                        .get(0);

        assertThat(f.risk()).contains("jev.example.internal");
    }

    @Test
    void everyHostedClassifierGetsItsOwnFinding() {
        String two =
                classifier("Jev/jev-latest", "")
                        + "intentclassifier Other { model: \"gemini-2.5-flash\" choose k: a | b }\n";

        assertThat(la19(two))
                .extracting(Finding::where)
                .containsExactlyInAnyOrder("intentclassifier Intent", "intentclassifier Other");
    }

    @Test
    void theAuditPageListsTheRule() throws Exception {
        assertThat(Files.readString(Path.of("../../../docs/security/weave-audit.md")))
                .contains("| LA19 |");
    }

    @Test
    void theOpenAiFamilyVendorsAreListedWithTheirHosts() {
        assertThat(la19(classifier("gpt-5-mini", "")).get(0).risk())
                .contains("OpenAI")
                .contains("api.openai.com");
        assertThat(la19(classifier("grok-4", "")).get(0).risk())
                .contains("xAI")
                .contains("api.x.ai");
        assertThat(la19(classifier("kimi/kimi-k3", "")).get(0).risk())
                .contains("Kimi")
                .contains("api.moonshot.ai");
        assertThat(la19(classifier("qwen/qwen3-max", "")).get(0).risk())
                .contains("Qwen")
                .contains("dashscope-intl.aliyuncs.com");
        assertThat(la19(classifier("perplexity/openai/gpt-5.6-sol", "")).get(0).risk())
                .contains("Perplexity")
                .contains("api.perplexity.ai");
    }

    @Test
    void aDeclaredVendorProviderNamesItsDefaultHostAndALocalServerIsNotListed() {
        String declared =
                "provider Mine { use: kimi  api_key: env.K }\n" + classifier("Mine/kimi-k3", "");
        assertThat(la19(declared).get(0).risk()).contains("Kimi").contains("api.moonshot.ai");
        String local =
                "provider Box { use: openai_compatible  base_url: \"http://localhost:8000/v1\" }\n"
                        + classifier("Box/qwen3", "");
        assertThat(la19(local)).isEmpty();
        String remote =
                "provider Box { use: openai_compatible  base_url: \"https://gpu.example/v1\" }\n"
                        + classifier("Box/qwen3", "");
        assertThat(la19(remote).get(0).risk()).contains("gpu.example");
    }
}
