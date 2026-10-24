package io.github.llm4j.spring;

import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.secret.SecretStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** The scripted models that {@link LoomTest} puts in place of the real ones. */
@Configuration(proxyBeanMethods = false)
class LoomTestConfiguration {

    @Bean
    ScriptedLoomModels scriptedLoomModels() {
        return new ScriptedLoomModels();
    }

    /** Any key a model asks for exists, and is worthless: the scripted models never use it. */
    @Bean
    @Primary
    SecretStore loomTestSecrets() {
        return new SecretStore() {
            @Override
            public String resolve(String name) {
                return "loom-test-key";
            }

            @Override
            public String resolveFor(String name, String host) {
                return resolve(name);
            }

            @Override
            public boolean contains(String name) {
                return true;
            }

            @Override
            public java.util.Set<String> names() {
                return java.util.Set.of();
            }

            @Override
            public java.util.Optional<io.github.llm4j.secret.SecretMetadata> metadata(String name) {
                return java.util.Optional.empty();
            }

            @Override
            public void close() {}
        };
    }

    @Bean
    @Primary
    LLMClientFactory loomTestModels(ScriptedLoomModels models) {
        return models;
    }
}
