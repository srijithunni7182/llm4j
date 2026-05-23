package io.github.llm4j.tantrik.console.config;

import io.github.llm4j.engram.core.EngramEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a single {@link EngramEngine} instance as a Spring bean.
 *
 * <p>The engine is backed by an {@code InMemoryStore} that optionally persists
 * its state to a JSON file at the path configured by
 * {@code tantrik.console.engram.storage-path}.  When the property is blank or
 * absent the store is purely in-memory (no persistence across restarts).
 *
 * <p>Design decision: a single shared instance is sufficient for the console
 * because all Loom runs execute in the same JVM process.
 */
@Configuration
public class EngramConfig {

    @Bean
    public EngramEngine engramEngine(
            @Value("${tantrik.console.engram.storage-path:}") String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return new EngramEngine();
        }
        return new EngramEngine(storagePath);
    }
}
