package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.inspect.control.Controls;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretMetadata;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What the secrets page lists and what a rotation changes. */
class EmbeddedSecretsTest {

    private static final String SCRIPT = """
            provider Box { use: sarvam  api_key: secret.BOX_KEY  base_url: "https://gpu.example.com" }
            tool Search { use: serpapi  api_key: secret.SERP_KEY }
            agent Researcher { model: "Box/llama" tools: [Search] }
            agent Writer { model: "Box/llama" }
            workflow Main() { delegate "x" to Researcher -> r }
            """;

    private final InMemorySecretStore store = new InMemorySecretStore();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneOffset.UTC);

    private io.github.llm4j.loom.observe.ObservableRun run() {
        io.github.llm4j.loom.execution.HarnessExecutor e = new io.github.llm4j.loom.execution.HarnessExecutor(script(), new io.github.llm4j.loom.execution.ToolRegistry(), model -> null);
        e.setSecretStore(store);
        return e.observable();
    }

    private LoomScript script() {
        return new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
    }

    @Test
    void whoUsesASecretIsReadFromTheScript() {
        Map<String, Set<String>> used = EmbeddedSecrets.usedBy(script());
        assertThat(used.get("BOX_KEY")).containsExactly("Researcher (provider Box)", "Writer (provider Box)");
        assertThat(used.get("SERP_KEY")).containsExactly("Researcher (tool Search)");
    }

    @Test
    void theListShowsHostsFingerprintAndWhatIsMissing_butNeverAValue() {
        store.put("BOX_KEY", "sk-box-secret-value", SecretMetadata.allowing("gpu.example.com"));
        io.github.llm4j.loom.observe.ObservableRun run = run();
        List<Map<String, Object>> rows = new EmbeddedSecrets(() -> run, clock).list();
        Map<String, Object> box = rows.stream().filter(r -> r.get("name").equals("BOX_KEY")).findFirst().orElseThrow();
        assertThat(box).containsEntry("present", true).containsEntry("allowedHosts", List.of("gpu.example.com"));
        assertThat(box.get("fingerprint").toString()).matches("[0-9a-f]{4}…[0-9a-f]{2}");
        Map<String, Object> serp = rows.stream().filter(r -> r.get("name").equals("SERP_KEY")).findFirst().orElseThrow();
        assertThat(serp).containsEntry("present", false).doesNotContainKey("fingerprint");
        assertThat(rows.toString()).doesNotContain("sk-box-secret-value");
    }

    @Test
    void aRotationReplacesTheValueKeepsTheHostsAndStampsTheTime_andCannotAddASecret() {
        store.put("BOX_KEY", "old-value-1", SecretMetadata.allowing("gpu.example.com"));
        EmbeddedSecrets secrets = new EmbeddedSecrets(() -> run(), clock);
        Controls.Outcome o = secrets.rotate("BOX_KEY", "new-value-2".toCharArray(), "admin@x");
        assertThat(o.accepted()).isTrue();
        assertThat(o.message()).doesNotContain("new-value-2").doesNotContain("old-value-1");
        assertThat(store.resolve("BOX_KEY")).isEqualTo("new-value-2");
        assertThat(store.metadata("BOX_KEY").orElseThrow().allowedHosts()).containsExactly("gpu.example.com");
        assertThat(store.metadata("BOX_KEY").orElseThrow().updatedAt()).isNotNull(); // the store stamps it

        Controls.Outcome none = secrets.rotate("SERP_KEY", "brand-new".toCharArray(), "admin@x");
        assertThat(none.accepted()).isFalse();
        assertThat(none.status()).isEqualTo(404);
        assertThat(store.contains("SERP_KEY")).isFalse();
    }
}
