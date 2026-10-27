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

    /**
     * The application's own secret store stays: what it holds is used, and what it lacks is still
     * reported missing. Only the keys of the model providers exist (worthless: the scripted models
     * never use them), so a script can start without them.
     */
    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor loomTestSecrets() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof SecretStore store && !(bean instanceof ProviderKeys)
                        ? new ProviderKeys(store)
                        : bean;
            }
        };
    }

    private static final java.util.Set<String> PROVIDER_KEYS =
            java.util.Set.of(
                    "GEMINI_API_KEY",
                    "ANTHROPIC_API_KEY",
                    "OPENAI_API_KEY",
                    "XAI_API_KEY",
                    "SARVAM_API_KEY",
                    "MOONSHOT_API_KEY",
                    "DASHSCOPE_API_KEY",
                    "PERPLEXITY_API_KEY",
                    "JEV_API_KEY");

    /** A secret store that also has the provider keys, unless the real one has them. */
    private static final class ProviderKeys implements SecretStore {
        private final SecretStore real;

        ProviderKeys(SecretStore real) {
            this.real = real;
        }

        private boolean fake(String name) {
            return PROVIDER_KEYS.contains(name) && !real.contains(name);
        }

        @Override
        public String resolve(String name) {
            return fake(name) ? "loom-test-key" : real.resolve(name);
        }

        @Override
        public String resolveFor(String name, String host) {
            return fake(name) ? "loom-test-key" : real.resolveFor(name, host);
        }

        @Override
        public boolean contains(String name) {
            return fake(name) || real.contains(name);
        }

        @Override
        public java.util.Set<String> names() {
            return real.names();
        }

        @Override
        public java.util.Optional<io.github.llm4j.secret.SecretMetadata> metadata(String name) {
            return real.metadata(name);
        }

        @Override
        public void put(String name, char[] value, io.github.llm4j.secret.SecretMetadata metadata) {
            real.put(name, value, metadata);
        }

        @Override
        public boolean remove(String name) {
            return real.remove(name);
        }

        @Override
        public void close() {
            real.close();
        }
    }

    @Bean
    @Primary
    LLMClientFactory loomTestModels(ScriptedLoomModels models) {
        return models;
    }
}
