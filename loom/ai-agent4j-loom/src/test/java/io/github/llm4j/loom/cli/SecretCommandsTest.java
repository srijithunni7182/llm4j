package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.MasterKey;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI-*: {@code weave secrets} and {@code --secrets}: values are typed or piped, never arguments, and never printed. */
class SecretCommandsTest {

    static final String VALUE = "ZZ-cli-secret-value-77";
    static final String PASS = "correct horse battery staple";

    @TempDir
    Path dir;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Map<String, String> vars = new java.util.HashMap<>();

    /** Answers prompts in order, and remembers what it was asked. */
    static final class Script implements Prompts {
        final Deque<String> answers = new ArrayDeque<>();
        final List<String> asked = new ArrayList<>();
        String line;

        Script(String... a) {
            answers.addAll(List.of(a));
        }

        @Override
        public char[] readSecret(String prompt) {
            asked.add(prompt);
            return answers.removeFirst().toCharArray();
        }

        @Override
        public String readLine() {
            return line;
        }
    }

    WeaveEnv env() {
        return new WeaveEnv(m -> null, q -> "", new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), vars::get, null);
    }

    /** An environment whose models come from the real default factory, so key lookups are real. */
    WeaveEnv realModels() {
        WeaveEnv e = env();
        return new WeaveEnv(new io.github.llm4j.loom.execution.DefaultLLMClientFactory(vars::get), e.human(), e.out(), e.err(), e.clock(), e.sleeper(),
                e.commands(), e.weave(), vars::get, null);
    }

    String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    Path file() {
        return dir.resolve("keys.store");
    }

    @Test
    void cli_lifecycle_neverShowsOrStoresValueInTheClear() throws Exception {
        String f = file().toString();
        assertThat(SecretCommands.run(env(), new Script(PASS, PASS), "create", "--secrets", f, "--iterations", "100000")).isZero();
        assertThat(SecretCommands.run(env(), new Script(VALUE, PASS), "set", "GEMINI_API_KEY", "--secrets", f,
                "--allow-host", "generativelanguage.googleapis.com", "--description", "gemini")).isZero();
        assertThat(SecretCommands.run(env(), new Script(PASS), "list", "--secrets", f)).isZero();
        assertThat(said()).contains("GEMINI_API_KEY").contains("generativelanguage.googleapis.com").doesNotContain(VALUE);
        assertThat(Files.readString(file(), StandardCharsets.ISO_8859_1)).doesNotContain(VALUE).doesNotContain("GEMINI_API_KEY");

        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.open(file(), MasterKey.of(PASS.toCharArray()))) {
            assertThat(s.resolve("GEMINI_API_KEY")).isEqualTo(VALUE);
        }
        assertThat(SecretCommands.run(env(), new Script(PASS), "remove", "GEMINI_API_KEY", "--secrets", f)).isZero();
        assertThat(SecretCommands.run(env(), new Script(PASS), "remove", "GEMINI_API_KEY", "--secrets", f)).isEqualTo(1);
    }

    @Test
    void cli_createRefusesDifferingEntriesAndExistingFile() {
        String f = file().toString();
        assertThat(SecretCommands.run(env(), new Script("one", "two"), "create", "--secrets", f)).isEqualTo(1);
        assertThat(said()).contains("differ");
        assertThat(file()).doesNotExist();
        assertThat(SecretCommands.run(env(), new Script(PASS, PASS), "create", "--secrets", f, "--iterations", "100000")).isZero();
        assertThat(SecretCommands.run(env(), new Script(PASS, PASS), "create", "--secrets", f, "--iterations", "100000")).isEqualTo(1);
    }

    @Test
    void cli_setReadsStdinAndKeyFromNamedVariable() throws Exception {
        String f = file().toString();
        vars.put("MY_STORE_KEY", PASS);
        assertThat(SecretCommands.run(env(), new Script(), "create", "--secrets", f, "--secrets-key-env", "MY_STORE_KEY", "--iterations", "100000")).isZero();
        Script piped = new Script();
        piped.line = VALUE;
        assertThat(SecretCommands.run(env(), piped, "set", "TOKEN", "--stdin", "--secrets", f, "--secrets-key-env", "MY_STORE_KEY")).isZero();
        assertThat(piped.asked).isEmpty();
        assertThat(said()).doesNotContain(VALUE);
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.open(file(), MasterKey.of(PASS.toCharArray()))) {
            assertThat(s.resolve("TOKEN")).isEqualTo(VALUE);
        }
    }

    @Test
    void cli_importEnvCopiesWithoutEchoAndReportsMissing() throws Exception {
        String f = file().toString();
        SecretCommands.run(env(), new Script(PASS, PASS), "create", "--secrets", f, "--iterations", "100000");
        vars.put("SARVAM_API_KEY", VALUE);
        assertThat(SecretCommands.run(env(), new Script(PASS), "import-env", "SARVAM_API_KEY", "NOPE_NOT_SET", "--secrets", f)).isEqualTo(1);
        assertThat(said()).contains("Imported SARVAM_API_KEY").contains("NOPE_NOT_SET is not set").doesNotContain(VALUE);
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.open(file(), MasterKey.of(PASS.toCharArray()))) {
            assertThat(s.resolve("SARVAM_API_KEY")).isEqualTo(VALUE);
        }
    }

    @Test
    void cli_rekeyChangesTheMasterKey() throws Exception {
        String f = file().toString();
        SecretCommands.run(env(), new Script(PASS, PASS), "create", "--secrets", f, "--iterations", "100000");
        vars.put("SARVAM_API_KEY", VALUE);
        SecretCommands.run(env(), new Script(PASS), "import-env", "SARVAM_API_KEY", "--secrets", f);
        assertThat(SecretCommands.run(env(), new Script(PASS, "new-pass", "new-pass"), "rekey", "--secrets", f)).isZero();
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.open(file(), MasterKey.of("new-pass".toCharArray()))) {
            assertThat(s.resolve("SARVAM_API_KEY")).isEqualTo(VALUE);
        }
        assertThat(SecretCommands.run(env(), new Script(PASS), "list", "--secrets", f)).isEqualTo(1);
    }

    @Test
    void cli_missingFileOptionAndWrongKeyAreCleanErrors() {
        assertThat(SecretCommands.run(env(), new Script(), "list")).isEqualTo(2);
        assertThat(said()).contains("--secrets <file> is required");
        String f = file().toString();
        SecretCommands.run(env(), new Script(PASS, PASS), "create", "--secrets", f, "--iterations", "100000");
        assertThat(SecretCommands.run(env(), new Script("wrong-" + PASS), "list", "--secrets", f)).isEqualTo(1);
        assertThat(said()).doesNotContain(PASS);
    }

    @Test
    void cli_secretsOption_opensTheStoreForRunAndCheck() throws Exception {
        String f = file().toString();
        vars.put("MY_STORE_KEY", PASS);
        SecretCommands.run(env(), new Script(), "create", "--secrets", f, "--secrets-key-env", "MY_STORE_KEY", "--iterations", "100000");
        Script piped = new Script();
        piped.line = VALUE;
        SecretCommands.run(env(), piped, "set", "SARVAM_API_KEY", "--stdin", "--secrets", f, "--secrets-key-env", "MY_STORE_KEY");

        SecretOptions o = new SecretOptions();
        o.file = new File(f);
        o.keyEnv = "MY_STORE_KEY";
        WeaveEnv opened = o.apply(realModels(), new Script());
        assertThat(opened).isNotNull();
        assertThat(opened.secrets().resolve("SARVAM_API_KEY")).isEqualTo(VALUE);

        Path script = dir.resolve("w.loom");
        Files.writeString(script, """
                agent A { model: "sarvam/sarvam-m" }
                workflow Main() { delegate "hi" to A -> out }
                """);
        // the key is only in the store, not the environment: check passes only because the store was opened
        assertThat(WeaveCLI.check(script.toFile(), null, false, opened)).isZero();
        assertThat(WeaveCLI.check(script.toFile(), null, false, realModels())).isNotZero();
        assertThat(said()).doesNotContain(VALUE);

        // a wrong key is reported without the key or the value
        vars.put("MY_STORE_KEY", "not-the-key");
        assertThat(o.apply(env(), new Script())).isNull();
        assertThat(said()).doesNotContain(VALUE).doesNotContain("not-the-key");
    }

    @Test
    void cli_secretsOptionNeedsAFileAndNothingIsPersistedInTheRunSpec() {
        SecretOptions o = new SecretOptions();
        o.keyEnv = "X";
        assertThat(o.apply(env(), new Script())).isNull();
        assertThat(said()).contains("need --secrets");
        // a run's saved spec has no place for the store's path or key source
        for (var c : RunSpec.class.getRecordComponents()) assertThat(c.getName().toLowerCase()).doesNotContain("secret").doesNotContain("master");
    }
}
