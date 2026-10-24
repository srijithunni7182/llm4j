package io.github.llm4j.loom.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The projects {@code weave try} runs: real Loom projects shipped inside the jar, each with a demo
 * script that is also its test suite. Adding one is adding a folder under {@code try/} and a line
 * in {@code try/index.txt}; there is no code per scenario.
 */
public final class Scenarios {

    private Scenarios() {}

    public record Scenario(String name, String description, String shows) {}

    /** The files of every scenario, relative to its folder. */
    static final List<String> FILES = List.of("main.loom", "tests/main.test.yaml");

    public static List<Scenario> list() {
        List<Scenario> out = new ArrayList<>();
        for (String line : text("try/index.txt").split("\n")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\|", 3);
            out.add(new Scenario(p[0].trim(), p[1].trim(), p.length > 2 ? p[2].trim() : ""));
        }
        return out;
    }

    public static Scenario find(String name) {
        return list().stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    /** Writes the scenario's project into {@code dir}. */
    public static void write(Scenario s, Path dir) throws IOException {
        for (String f : FILES) {
            Path target = dir.resolve(f);
            Files.createDirectories(target.getParent());
            Files.writeString(target, text("try/" + s.name() + "/" + f), StandardCharsets.UTF_8);
        }
    }

    private static String text(String resource) {
        try (InputStream in = Scenarios.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
