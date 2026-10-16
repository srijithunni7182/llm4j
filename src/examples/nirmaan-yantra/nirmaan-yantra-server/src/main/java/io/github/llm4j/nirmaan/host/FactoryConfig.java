package io.github.llm4j.nirmaan.host;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Where the web host finds things. Every value has a default that works when the host is started from the project folder
 * ({@code nirmaan-yantra-server/}); each can be overridden with an environment variable.
 *
 * @param script       the workflow ({@code NIRMAAN_SCRIPT}, default {@code src/main/resources/main.loom})
 * @param prices       the price table the script's cost budget needs ({@code NIRMAAN_PRICES}, default {@code prices.properties})
 * @param secretsStore the encrypted secret store holding the model key ({@code NIRMAAN_SECRETS}, default {@code .loom/secrets.store})
 * @param secretsKey   the file whose first line is the store's master key ({@code NIRMAAN_SECRETS_KEY}, default {@code .loom/secrets.key})
 * @param uiDir        the built React UI ({@code NIRMAAN_UI_DIR}, default {@code ../nirmaan-yantra-ui/dist})
 * @param bind         the address to listen on: loopback only ({@code NIRMAAN_BIND}, default 127.0.0.1)
 * @param port         ({@code NIRMAAN_PORT}, default 8080; 0 picks a free one)
 * @param fabricUrl    a Fabric server to report every run to, or empty ({@code NIRMAAN_FABRIC_URL})
 * @param fabricName   the name this process has on that Fabric ({@code NIRMAAN_FABRIC_NAME}, default {@code nirmaan})
 */
public record FactoryConfig(Path script, Path prices, Path secretsStore, Path secretsKey, Path uiDir, String bind, int port, String fabricUrl, String fabricName) {

    public static FactoryConfig fromEnvironment() {
        return from(System::getenv);
    }

    static FactoryConfig from(Function<String, String> env) {
        return new FactoryConfig(
                path(env, "NIRMAAN_SCRIPT", "src/main/resources/main.loom"),
                path(env, "NIRMAAN_PRICES", "prices.properties"),
                path(env, "NIRMAAN_SECRETS", ".loom/secrets.store"),
                path(env, "NIRMAAN_SECRETS_KEY", ".loom/secrets.key"),
                path(env, "NIRMAAN_UI_DIR", "../nirmaan-yantra-ui/dist"),
                or(env.apply("NIRMAAN_BIND"), "127.0.0.1"),
                Integer.parseInt(or(env.apply("NIRMAAN_PORT"), "8080")),
                or(env.apply("NIRMAAN_FABRIC_URL"), ""),
                or(env.apply("NIRMAAN_FABRIC_NAME"), "nirmaan"));
    }

    private static Path path(Function<String, String> env, String name, String fallback) {
        return Path.of(or(env.apply(name), fallback)).toAbsolutePath().normalize();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
