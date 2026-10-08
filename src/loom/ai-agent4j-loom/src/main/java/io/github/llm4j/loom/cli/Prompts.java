package io.github.llm4j.loom.cli;

import io.github.llm4j.secret.SecretStoreException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Where {@code weave} asks for a passphrase or a secret value: never from an argument, and never echoed. Replaceable, so tests need no console. */
interface Prompts {

    /** Reads a line without echoing it. */
    char[] readSecret(String prompt);

    /** Reads one line from standard input (for scripts that pipe a value in). */
    String readLine();

    /** The process's console and standard input. */
    static Prompts console() {
        return new Prompts() {
            @Override
            public char[] readSecret(String prompt) {
                java.io.Console console = System.console();
                if (console == null) {
                    throw new SecretStoreException("there is no console to ask for \"" + prompt.trim()
                            + "\"; give --secrets-key-file or --secrets-key-env, or run weave in a terminal");
                }
                char[] value = console.readPassword("%s", prompt);
                if (value == null || value.length == 0) throw new SecretStoreException("nothing was entered");
                return value;
            }

            @Override
            public String readLine() {
                try {
                    return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                } catch (IOException e) {
                    throw new SecretStoreException("cannot read standard input: " + e.getMessage(), e);
                }
            }
        };
    }
}
