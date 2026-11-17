package io.github.llm4j.loom.cli;

import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretStore;
import java.io.File;
import java.util.Arrays;
import picocli.CommandLine.Option;

/**
 * {@code --secrets <file>} and where its master key comes from. The consumer chooses all of it:
 * there is no default location and no default key source. {@code --secrets-key-env} names the
 * variable (it is not read from a fixed one). With neither key option the passphrase is asked for
 * on the console.
 *
 * <p>The store and its key file may instead be named once in the environment, as {@value
 * #STORE_VARIABLE} and {@value #KEY_FILE_VARIABLE}, both or neither, so a person need not repeat
 * them on every command. Any store option on the command line wins over both.
 *
 * <p>Protecting the file and its directory (an ACL) is the consumer's responsibility. Nothing here
 * is written to a run's saved spec: resuming a run needs these options again.
 */
final class SecretOptions {

    /** The environment variable that names the store when {@code --secrets} is not given. */
    static final String STORE_VARIABLE = "LOOM_SECRETS";

    /** The environment variable that names the master key file when no key option is given. */
    static final String KEY_FILE_VARIABLE = "LOOM_SECRETS_KEY_FILE";

    @Option(
            names = "--secrets",
            paramLabel = "<file>",
            description =
                    "An encrypted secret store (see 'weave secrets'). Its location and master key are yours to choose and protect.")
    File file;

    @Option(
            names = "--secrets-key-file",
            paramLabel = "<file>",
            description =
                    "A file whose first line is the store's master key (protect it with an ACL).")
    File keyFile;

    @Option(
            names = "--secrets-key-env",
            paramLabel = "<VARIABLE>",
            description =
                    "The environment variable that holds the store's master key (you choose its name).")
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
                throw new io.github.llm4j.secret.SecretStoreException(
                        "--secrets-key-file and --secrets-key-env need --secrets <file>");
            }
            return null;
        }
        MasterKey key = masterKey(env, prompts, "Master key for " + file.getName() + ": ");
        return EncryptedFileSecretStore.open(file.toPath(), key);
    }

    /**
     * The environment with the store opened, or null (after saying why) when it could not be
     * opened.
     */
    WeaveEnv apply(WeaveEnv env, Prompts prompts) {
        try {
            fromEnvironment(env.env());
            if (file == null && keyFile == null && keyEnv == null) return env;
            return env.withSecrets(open(env, prompts));
        } catch (io.github.llm4j.secret.SecretException e) {
            env.err().println("Error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fills in the store and its key file from {@value #STORE_VARIABLE} and {@value
     * #KEY_FILE_VARIABLE}, only when the command line named neither the store nor a key source and
     * both variables are set: an environment variable never makes a command open a store it did
     * not open before, and never makes it ask for a passphrase.
     *
     * @throws io.github.llm4j.secret.SecretStoreException when only one of the two is set, naming it
     */
    void fromEnvironment(java.util.function.Function<String, String> env) {
        if (file != null || keyFile != null || keyEnv != null) return;
        String store = env.apply(STORE_VARIABLE);
        String key = env.apply(KEY_FILE_VARIABLE);
        boolean hasStore = store != null && !store.isBlank();
        boolean hasKey = key != null && !key.isBlank();
        if (hasStore != hasKey)
            throw new io.github.llm4j.secret.SecretStoreException(
                    (hasStore ? STORE_VARIABLE : KEY_FILE_VARIABLE)
                            + " is set but "
                            + (hasStore ? KEY_FILE_VARIABLE : STORE_VARIABLE)
                            + " is not: set both to name the store once, or neither");
        if (!hasStore) return;
        file = new File(store.strip());
        keyFile = new File(key.strip());
    }

    /** Wipes an array (a passphrase read from the console). */
    static void wipe(char[] chars) {
        if (chars != null) Arrays.fill(chars, '\0');
    }
}
