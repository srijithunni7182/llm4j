package io.github.llm4j.evalreport.format;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Writes a bundle directory: used by merge and legacy import. */
public final class BundleWriter {

    private BundleWriter() {}

    public static Path write(Path root, RunBundle b, JsonNode runJson) throws IOException {
        Path dir = root.resolve("runs").resolve(b.run().runId());
        Files.createDirectories(dir);
        Path tmp = dir.resolve("run.json.tmp");
        RunBundleReader.MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), runJson);
        Files.move(tmp, dir.resolve("run.json"), StandardCopyOption.REPLACE_EXISTING);
        lines(dir.resolve("evaluations.jsonl"), b.evaluations());
        lines(dir.resolve("scenarios.jsonl"), b.scenarios());
        lines(dir.resolve("tests.jsonl"), b.tests());
        lines(dir.resolve("traces.jsonl"), b.traces());
        lines(dir.resolve("optimizations.jsonl"), b.optimizations());
        return dir;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OUT =
            RunBundleReader.MAPPER
                    .copy()
                    .setSerializationInclusion(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    private static void lines(Path file, List<?> items) throws IOException {
        if (items.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : items) {
            sb.append(OUT.writeValueAsString(o)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
