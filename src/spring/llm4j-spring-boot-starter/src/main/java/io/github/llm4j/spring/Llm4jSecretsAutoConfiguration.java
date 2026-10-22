package io.github.llm4j.spring;

import io.github.llm4j.secret.ChainedSecretStore;
import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.EnvSecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The secret store for keys and passwords: {@code llm4j.secrets.store} = {@code env} (the Spring
 * {@code Environment}), {@code encrypted-file}, or {@code chained}. Plain Java: {@code
 * EnvSecretStore.of(lookup)}, {@code EncryptedFileSecretStore.openOrCreate(file, masterKey)},
 * {@code ChainedSecretStore.of(a, b)}. A {@code SecretStore} bean of your own replaces it.
 */
@AutoConfiguration(before = Llm4jClientsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "llm4j", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(Llm4jProperties.class)
public class Llm4jSecretsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j.secrets", name = "store")
    public SecretStore llm4jSecretStore(Llm4jProperties properties, Environment environment) {
        return create(properties.secrets().store(), properties.secrets(), environment::getProperty);
    }

    static SecretStore create(
            String kind, Llm4jProperties.Secrets s, Function<String, String> lookup) {
        return switch (kind.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "env" -> EnvSecretStore.of(lookup);
            case "encrypted-file" -> encrypted(s, lookup);
            case "chained" -> {
                if (s.chain() == null || s.chain().isEmpty()) {
                    throw new Llm4jConfigurationException(
                            "llm4j.secrets.store=chained needs llm4j.secrets.chain",
                            "List the stores to try in order, for example [encrypted-file, env].");
                }
                List<SecretStore> stores = new ArrayList<>();
                for (String each : s.chain()) {
                    if (each.equalsIgnoreCase("chained"))
                        throw new Llm4jConfigurationException(
                                "llm4j.secrets.chain cannot contain chained",
                                "Use env and encrypted-file.");
                    stores.add(create(each, s, lookup));
                }
                yield ChainedSecretStore.of(stores.toArray(new SecretStore[0]));
            }
            default -> throw new Llm4jConfigurationException(
                    "llm4j.secrets.store=" + kind + " is not known",
                    "Use env, encrypted-file or chained, or declare a SecretStore bean.");
        };
    }

    private static SecretStore encrypted(
            Llm4jProperties.Secrets s, Function<String, String> lookup) {
        if (s.file() == null || s.file().isBlank()) {
            throw new Llm4jConfigurationException(
                    "llm4j.secrets.store=encrypted-file needs llm4j.secrets.file",
                    "Give the path of the encrypted store file.");
        }
        boolean fromEnv = s.masterKeyEnv() != null && !s.masterKeyEnv().isBlank();
        boolean fromFile = s.masterKeyFile() != null && !s.masterKeyFile().isBlank();
        if (fromEnv == fromFile) {
            throw new Llm4jConfigurationException(
                    fromEnv
                            ? "llm4j.secrets.master-key-env and llm4j.secrets.master-key-file are both set"
                            : "llm4j.secrets.master-key-env is not set (nor master-key-file)",
                    "Name exactly one place the master key is read from: an environment variable (master-key-env) or a file (master-key-file). It is never a literal property.");
        }
        MasterKey key =
                fromEnv
                        ? MasterKey.fromEnv(s.masterKeyEnv(), lookup)
                        : MasterKey.fromFile(Path.of(s.masterKeyFile()));
        return EncryptedFileSecretStore.openOrCreate(Path.of(s.file()), key);
    }
}
