package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretMetadata;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code weave keys}: which key each model needs, whether it is set (never its value), and the
 * exact commands that set a missing one, so a person can be handed them before the first run.
 */
class KeysCommandTest {

    private static final String FAKE_KEY = "sk-test-000-not-a-real-key";

    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<String, String> vars = new HashMap<>();

    private int keys(String... args) {
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        vars::get,
                        null);
        KeysCommand c = new KeysCommand();
        new picocli.CommandLine(c).parseArgs(args);
        return KeysCommand.keys(c, env, dir.resolve("home"));
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aModelWithNoKeyGetsTheExactCommandsThatSetIt() {
        int code = keys("--model", "gpt-4.1-mini");

        String store = dir.resolve("home/.llm4j/secrets.store").toString();
        String key = dir.resolve("home/.llm4j/secrets.key").toString();
        assertThat(code).as("a key is missing").isEqualTo(1);
        assertThat(said())
                .contains("gpt-4.1-mini")
                .contains("OPENAI_API_KEY")
                .contains("not set")
                .contains("weave secrets create --secrets " + store + " --secrets-key-file " + key)
                .contains("weave secrets set OPENAI_API_KEY --secrets " + store + " --secrets-key-file " + key)
                .contains("LOOM_SECRETS=" + store)
                .contains("LOOM_SECRETS_KEY_FILE=" + key)
                .contains("in your own terminal");
    }

    @Test
    void aKeyAlreadyInTheEnvironmentIsReportedAsSetAndNeverShown() {
        vars.put("OPENAI_API_KEY", FAKE_KEY);

        int code = keys("--model", "gpt-4.1-mini");

        assertThat(code).isZero();
        assertThat(said()).contains("OPENAI_API_KEY").contains("set in the environment").doesNotContain(FAKE_KEY).doesNotContain("weave secrets set");
    }

    @Test
    void aKeyInTheStoreIsFoundThroughTheEnvironmentsStoreSettings() throws Exception {
        Path store = dir.resolve("keys.store");
        Path keyFile = Files.writeString(dir.resolve("keys.key"), "a-master-key-for-the-test-only\n");
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.create(store, MasterKey.fromFile(keyFile))) {
            s.put("ANTHROPIC_API_KEY", FAKE_KEY.toCharArray(), SecretMetadata.allowing("api.anthropic.com"));
        }
        vars.put("LOOM_SECRETS", store.toString());
        vars.put("LOOM_SECRETS_KEY_FILE", keyFile.toString());

        int code = keys("--model", "claude-sonnet-5-5");

        assertThat(code).isZero();
        assertThat(said()).contains("ANTHROPIC_API_KEY").contains("set in the secret store " + store).doesNotContain(FAKE_KEY);
    }

    @Test
    void theModelsOfAScriptAreReadFromItAndALocalModelNeedsNoKey() throws Exception {
        Path script =
                Files.writeString(
                        dir.resolve("main.loom"),
                        """
                        agent Writer { model: "gemini-2.5-flash" system: "You write." }
                        agent Local { model: "ollama/llama3" system: "You help." }
                        extractor Reader { model: "claude-haiku-5-5"  fields { name: string } }
                        workflow Main(q) {
                            extract Reader from q -> r
                            delegate "{r.name}" to Writer -> w
                            delegate "{w}" to Local -> l
                        }
                        """);

        keys(script.toString());

        assertThat(said())
                .contains("gemini-2.5-flash").contains("GEMINI_API_KEY")
                .contains("claude-haiku-5-5").contains("ANTHROPIC_API_KEY")
                .contains("ollama/llama3").contains("no key: it runs on this computer");
    }

    @Test
    void aStoreNamedOnTheCommandLineIsTheOneTheCommandsUse() {
        Path store = dir.resolve("team.store");
        Path keyFile = dir.resolve("team.key");

        keys("--model", "grok-4", "--secrets", store.toString(), "--secrets-key-file", keyFile.toString());

        assertThat(said()).contains("XAI_API_KEY").contains("weave secrets set XAI_API_KEY --secrets " + store + " --secrets-key-file " + keyFile);
    }

    @Test
    void aModelItCannotPlaceIsSaidPlainly() {
        int code = keys("--model", "my-own-model");

        assertThat(code).isEqualTo(1);
        assertThat(said()).contains("my-own-model").contains("unknown model");
    }

    @Test
    void withNoScriptAndNoModelItSaysWhatToGive() {
        assertThat(keys()).isEqualTo(2);
        assertThat(said()).contains("--model");
    }

    @Test
    void eachKeyIsListedOnceWhenSeveralModelsShareIt() {
        keys("--model", "gpt-4.1-mini", "--model", "gpt-5");

        List<String> setLines = new ArrayList<>();
        for (String line : said().split("\n")) if (line.contains("weave secrets set")) setLines.add(line);
        assertThat(setLines).hasSize(1);
    }

    @Test
    void aProviderTheScriptDeclaresNamesItsOwnKeyAndAClassifierModelTakesItsVendors() throws Exception {
        Path script =
                Files.writeString(
                        dir.resolve("main.loom"),
                        """
                        provider Box { use: openai_compatible  base_url: "http://localhost:8000/v1" }
                        provider Gate { use: openai  api_key: env.GATEWAY_KEY }
                        agent Local { model: "Box/llama3" system: "You help." }
                        agent Remote { model: "Gate/gpt-4.1-mini" system: "You help." }
                        intentclassifier Route {
                            model: "jev-latest"
                            choose kind: refund | other
                        }
                        workflow Main(q) {
                            intentclassify Route on q -> r
                            delegate "{q}" to Local -> a
                            delegate "{a}" to Remote -> b
                        }
                        """);

        keys(script.toString());

        assertThat(said())
                .contains("Box/llama3").contains("no key")
                .contains("Gate/gpt-4.1-mini").contains("GATEWAY_KEY")
                .contains("jev-latest").contains("JEV_API_KEY")
                .doesNotContain("unknown model");
    }
}
