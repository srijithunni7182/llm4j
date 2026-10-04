package io.github.llm4j.loom.cli;

import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretStore;
import java.io.File;
import java.util.Arrays;
import picocli.CommandLine.Option;

/**
 * {@code --secrets <file>} and where its master key comes from. The consumer chooses all of it: there is no default location and no default key
 * source. {@code --secrets-key-env} names the variable (it is not read from a fixed one). With neither key option the passphrase is asked for on
 * the console.
 *
 * <p>Protecting the file and its directory (an ACL) is the consumer's responsibility. Nothing here is written to a run's saved spec: resuming a
 * run needs these options again.
 */
final class SecretOptions {

    @Option(names = "--secrets", paramLabel = "<file>",
            description = "An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.")
    File file;

    @Option(names = "--secrets-key-file", paramLabel = "<file>",
            description = "A file whose first line is the store's master key (protect it with an ACL).")
    File keyFile;

    @Option(names = "--secrets-key-env", paramLabel = "<VARIABLE>",
            description = "The environment variable that holds the store's master key (you choose its name).")
    String keyEnv;

    boolean present() {
        return file != null;
    }

    /** The master key from the chosen source, or the console. */
    MasterKey masterKey(WeaveEnv env, Prompts prompts, String prompt) {
        if (keyFile != null) return MasterKey.fromFile(keyFile.toPath());
        if (keyEnv != null) return MasterKey.fromEnv(keyEnv, env.env());
        return MasterKey.of(prompts.readSecret(prompt));
    }

    /** Opens the store given with {@code --secrets}; null when none was given. */
    SecretStore open(WeaveEnv env, Prompts prompts) {
        if (file == null) {
            if (keyFile != null || keyEnv != null) {
                throw new io.github.llm4j.secret.SecretStoreException("--secrets-key-file and --secrets-key-env need --secrets <file>");
            }
            return null;
        }
        MasterKey key = masterKey(env, prompts, "Master key for " + file.getName() + ": ");
        return EncryptedFileSecretStore.open(file.toPath(), key);
    }

    /** The environment with the store opened, or null (after saying why) when it could not be opened. */
    WeaveEnv apply(WeaveEnv env, Prompts prompts) {
        if (file == null && keyFile == null && keyEnv == null) return env;
        try {
            return env.withSecrets(open(env, prompts));
        } catch (io.github.llm4j.secret.SecretException e) {
            env.err().println("Error: " + e.getMessage());
            return null;
        }
    }

    /** Wipes an array (a passphrase read from the console). */
    static void wipe(char[] chars) {
        if (chars != null) Arrays.fill(chars, '\0');
    }
}
