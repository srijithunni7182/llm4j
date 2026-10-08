package io.github.llm4j.hexamind.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Remembers what an agent produced for a scenario so a repeated or resumed run does not pay twice. The
 * key covers the scenario, the prompt version, the model and the recorded fixture; the {@code CURRENT
 * TIME} line the personas carry is left out because it changes every second.
 */
public final class ReplayCache {

    private static final Pattern CLOCK = Pattern.compile("CURRENT TIME[^\\n]*");

    private final Path dir;

    public ReplayCache(Path dir) {
        this.dir = dir;
    }

    public static String key(String scenarioId, String promptVersion, String model, String fixture) {
        return sha(String.join("\u0000", scenarioId, promptVersion, model, strip(fixture)));
    }

    static String strip(String s) {
        return s == null ? "" : CLOCK.matcher(s).replaceAll("CURRENT TIME");
    }

    public Optional<String> get(String key) {
        Path f = dir.resolve(key + ".json");
        try {
            return Files.exists(f) ? Optional.of(Files.readString(f)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void put(String key, String value) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".json"), value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
