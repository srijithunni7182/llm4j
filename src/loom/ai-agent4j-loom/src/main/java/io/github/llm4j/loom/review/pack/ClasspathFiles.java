package io.github.llm4j.loom.review.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The files of a pack stored as resources under one base path, such as {@code review-rules/core}.
 */
final class ClasspathFiles implements PackFiles {

    private final ClassLoader loader;
    private final String base;
    private final List<String> ruleFiles;

    ClasspathFiles(ClassLoader loader, String base, List<String> ruleFiles) {
        this.loader = loader;
        this.base = base.endsWith("/") ? base : base + "/";
        this.ruleFiles = List.copyOf(ruleFiles);
    }

    @Override
    public Optional<String> read(String relativePath) {
        if (relativePath.contains("..") || relativePath.startsWith("/")) return Optional.empty();
        try (InputStream in = loader.getResourceAsStream(base + relativePath)) {
            return in == null
                    ? Optional.empty()
                    : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> ruleFiles() {
        return ruleFiles;
    }

    @Override
    public String where() {
        return "classpath:" + base;
    }
}
