package io.github.llm4j.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists judge verdicts as small JSON files under a directory, so repeated {@code mvn test} runs
 * — including separate CI runs — don't re-spend judge calls on scenarios whose criterion, inputs,
 * and actual output haven't changed since the last run. Point it at a build-local directory (e.g.
 * {@code target/eval4j-cache}) or a CI-cached one, per your own caching setup; this class doesn't
 * pick a default location for you.
 *
 * <p>Not safe for concurrent writes to the same key from multiple processes; fine for the typical
 * single-JVM test-run use case this is built for.
 */
public final class FileSystemJudgeCache implements JudgeCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path cacheDir;

    private FileSystemJudgeCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new JudgeEvaluationException(
                    "Failed to create judge cache directory: " + cacheDir, e);
        }
    }

    public static FileSystemJudgeCache at(Path cacheDir) {
        return new FileSystemJudgeCache(cacheDir);
    }

    @Override
    public Optional<JudgeVerdict> get(String key) {
        Path file = fileFor(key);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), JudgeVerdict.class));
        } catch (IOException e) {
            // A corrupt or partially-written cache entry is treated as a miss so the condition
            // still gets a real answer by re-judging, rather than failing the test outright.
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, JudgeVerdict verdict) {
        try {
            MAPPER.writeValue(fileFor(key).toFile(), verdict);
        } catch (IOException e) {
            throw new JudgeEvaluationException(
                    "Failed to write judge cache entry to " + cacheDir, e);
        }
    }

    private Path fileFor(String key) {
        return cacheDir.resolve(key + ".json");
    }
}
