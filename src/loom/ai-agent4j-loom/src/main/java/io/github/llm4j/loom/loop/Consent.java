package io.github.llm4j.loom.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Capture puts text from real runs into a repository, so the first capture in a repository must be
 * agreed to, once, in a record that is itself committed ({@code .weave/capture.consent}).
 */
public final class Consent {

    private Consent() {}

    public static final String FILE = ".weave/capture.consent";

    public static final String NOTICE =
            """
            weave capture writes what a real run did into a test file in this repository: its inputs,
            what the models and classifiers answered, what people answered, and which steps failed.
            Names, emails, phone and card numbers it can find, and anything shaped like a key or a
            password, are replaced first; names and words a pattern cannot find are replaced only if
            you give them (--mask-term). The file is meant to be committed, so it is in the history of
            this repository for good. Read each case before you commit it.""";

    /** The folder consent is recorded in: the repository root above the script, else its folder. */
    public static Path root(Path script) {
        Path dir = script.toAbsolutePath().normalize().getParent();
        for (Path p = dir; p != null; p = p.getParent())
            if (Files.exists(p.resolve(".git"))) return p;
        return dir;
    }

    /** Whether it was agreed to; a record that cannot be read does not count. */
    public static boolean given(Path script) {
        Path file = root(script).resolve(FILE);
        if (!Files.isRegularFile(file)) return false;
        try {
            return new ObjectMapper().readTree(file.toFile()).hasNonNull("by");
        } catch (IOException e) {
            return false;
        }
    }

    /** Records that {@code by} agreed; a damaged record is replaced. */
    public static void record(Path script, String by) throws IOException {
        Path file = root(script).resolve(FILE);
        Files.createDirectories(file.getParent());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("by", by);
        m.put("at", Instant.now().toString());
        m.put("notice", NOTICE.replaceAll("\\s+", " ").trim());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), m);
    }
}
