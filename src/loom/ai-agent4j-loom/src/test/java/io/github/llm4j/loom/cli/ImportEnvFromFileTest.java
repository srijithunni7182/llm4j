package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.MasterKey;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F19: keys were typed into {@code .env.example}, a file meant to be committed, and the reporter
 * moved them by hand. {@code weave secrets import-env --from <file>} moves them into the store,
 * never prints a value, and offers to blank a template afterwards.
 */
class ImportEnvFromFileTest {
    private static final String PASS = SecretCommandsTest.PASS;
    private static final String KEY = "AIzaFakeKeyForATest0123456789abcdef";

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final Map<String, String> vars = new HashMap<>();

    private WeaveEnv env() {
        return new WeaveEnv(
                m -> null,
                q -> "",
                new PrintStream(out, true),
                new PrintStream(err, true),
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                vars::get,
                null);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private String store() throws Exception {
        String f = dir.resolve("keys.store").toString();
        SecretCommands.run(
                env(),
                new SecretCommandsTest.Script(PASS, PASS),
                "create",
                "--secrets",
                f,
                "--iterations",
                "100000");
        return f;
    }

    private Path template(String name) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(
                file,
                "# keys\nGEMINI_API_KEY=" + KEY + "\nLOG_LEVEL=debug\nANTHROPIC_API_KEY=\n");
        return file;
    }

    private int run(SecretCommandsTest.Script prompts, String... args) {
        return SecretCommands.run(env(), prompts, args);
    }

    @Test
    void theKeysOfAFileGoIntoTheStoreAndNoValueIsEverPrinted() throws Exception {
        String store = store();
        Path file = template(".env.example");
        SecretCommandsTest.Script prompts = new SecretCommandsTest.Script(PASS);
        prompts.line = "no";

        int code = run(prompts, "import-env", "--from", file.toString(), "--secrets", store);

        assertThat(code).isZero();
        assertThat(said())
                .contains("Imported GEMINI_API_KEY")
                .doesNotContain("LOG_LEVEL")
                .doesNotContain(KEY)
                .doesNotContain(KEY.substring(0, 6));
        try (EncryptedFileSecretStore s =
                EncryptedFileSecretStore.open(Path.of(store), MasterKey.of(PASS.toCharArray()))) {
            assertThat(s.resolve("GEMINI_API_KEY")).isEqualTo(KEY);
        }
    }

    @Test
    void aTemplateIsOfferedToBeBlankedAndYesBlanksOnlyTheImportedValues() throws Exception {
        String store = store();
        Path file = template(".env.example");
        SecretCommandsTest.Script prompts = new SecretCommandsTest.Script(PASS);
        prompts.line = "yes";

        run(prompts, "import-env", "--from", file.toString(), "--secrets", store);

        assertThat(Files.readString(file))
                .contains("# keys")
                .contains("GEMINI_API_KEY=\n")
                .contains("LOG_LEVEL=debug")
                .doesNotContain(KEY);
        assertThat(said()).contains("Blanked");
    }

    @Test
    void sayingNoLeavesTheTemplateAsItWas() throws Exception {
        String store = store();
        Path file = template(".env.example");
        String before = Files.readString(file);
        SecretCommandsTest.Script prompts = new SecretCommandsTest.Script(PASS);
        prompts.line = "no";

        run(prompts, "import-env", "--from", file.toString(), "--secrets", store);

        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    void noAnswerAtAllIsANoNotAYes() throws Exception {
        String store = store();
        Path file = template(".env.example");
        String before = Files.readString(file);

        run(new SecretCommandsTest.Script(PASS), "import-env", "--from", file.toString(), "--secrets", store);

        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    void dashBlankBlanksATemplateWithoutAsking() throws Exception {
        String store = store();
        Path file = template("keys.sample");

        run(new SecretCommandsTest.Script(PASS), "import-env", "--from", file.toString(), "--blank", "--secrets", store);

        assertThat(Files.readString(file)).doesNotContain(KEY).contains("GEMINI_API_KEY=\n");
    }

    @Test
    void aRealDotEnvIsNeverBlankedEvenWhenAsked() throws Exception {
        String store = store();
        Path file = template(".env");
        String before = Files.readString(file);

        run(new SecretCommandsTest.Script(PASS), "import-env", "--from", file.toString(), "--blank", "--secrets", store);

        assertThat(Files.readString(file)).isEqualTo(before);
        assertThat(said()).contains("Imported GEMINI_API_KEY");
    }

    @Test
    void namedVariablesLimitWhatIsImportedAndAMissingOneIsReported() throws Exception {
        String store = store();
        Path file = template(".env");

        int code =
                run(
                        new SecretCommandsTest.Script(PASS),
                        "import-env",
                        "GEMINI_API_KEY",
                        "NOT_THERE",
                        "--from",
                        file.toString(),
                        "--secrets",
                        store);

        assertThat(code).isEqualTo(1);
        assertThat(said()).contains("Imported GEMINI_API_KEY").contains("NOT_THERE is not set");
    }

    @Test
    void aFileWithNothingToImportSaysSoAndFails() throws Exception {
        String store = store();
        Path file = dir.resolve(".env.example");
        Files.writeString(file, "GEMINI_API_KEY=\nPORT=8080\n");

        int code = run(new SecretCommandsTest.Script(PASS), "import-env", "--from", file.toString(), "--secrets", store);

        assertThat(code).isEqualTo(1);
        assertThat(said()).contains("nothing to import");
    }

    @Test
    void withNoNamesAndNoFileItAsksForOne() throws Exception {
        String store = store();

        int code = run(new SecretCommandsTest.Script(PASS), "import-env", "--secrets", store);

        assertThat(code).isEqualTo(2);
        assertThat(said()).contains("--from");
    }
}
