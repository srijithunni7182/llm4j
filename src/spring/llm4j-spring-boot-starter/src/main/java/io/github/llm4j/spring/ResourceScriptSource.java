package io.github.llm4j.spring;

import io.github.llm4j.loom.execution.ScriptSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Reads scripts through Spring's {@code ResourceLoader}: {@code classpath:}, {@code file:}, or
 * inside a jar; an import is relative to the script that names it.
 */
final class ResourceScriptSource implements ScriptSource {

    private final ResourceLoader resources;

    ResourceScriptSource(ResourceLoader resources) {
        this.resources = resources;
    }

    @Override
    public String read(String location) throws IOException {
        try (InputStream in = resources.getResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public String resolve(String from, String imported) {
        try {
            Resource sibling = resources.getResource(from).createRelative(imported);
            return sibling.getURL().toExternalForm();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot find " + imported + " beside " + from + ": " + e.getMessage(), e);
        }
    }
}
