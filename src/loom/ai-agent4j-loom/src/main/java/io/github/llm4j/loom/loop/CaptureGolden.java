package io.github.llm4j.loom.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.loom.init.ProjectLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * A real run as an entry of the evaluation dataset ({@code eval/golden/workflow.yaml}), for judging
 * quality with real models. The input is masked like a captured test; the rubric is left for the
 * developer to write, because what a good answer is cannot be read from what happened.
 */
public final class CaptureGolden {

    private CaptureGolden() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML =
            YAMLMapper.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build();

    /**
     * @param file the dataset file
     * @param entry the entry as YAML
     * @param written true when the entry was added by this call
     * @param already true when an entry for this run was there
     */
    public record Entry(Path file, String entry, boolean written, boolean already) {}

    public static Entry capture(RunRecord run, Capture.Options options, boolean write)
            throws Exception {
        Masker masker = options.mask() ? new Masker(options.maskTerms()) : new Masker(List.of());
        String id = "captured-" + shortHash(run.id());
        ObjectNode e = JSON.createObjectNode();
        e.put("id", id);
        e.put("name", "Captured from run " + run.id());
        // one input: the text itself, or a map when the workflow took several
        if (run.inputs().size() == 1) {
            String key = run.inputs().keySet().iterator().next();
            e.put("input", mask(run, options, masker, key, run.inputs().get(key)));
        } else {
            ObjectNode in = e.putObject("input");
            run.inputs().forEach((k, v) -> in.put(k, mask(run, options, masker, k, v)));
        }
        ArrayNode dimensions = e.putArray("dimensions");
        dimensions.add("quality");
        ArrayNode rubric = e.putArray("rubric");
        rubric.add(
                "TODO: say what a good answer to this input does (this entry was captured from a real run and has no rubric yet)");
        ObjectNode tags = e.putObject("tags");
        tags.put("source", "captured");
        if (options.note() != null && !options.note().isBlank())
            tags.put("note", masker.mask(options.note()));

        Path script = run.script();
        Path dir = ProjectLayout.dataset(script);
        Path file = dir.resolve("workflow.yaml");
        ArrayNode all = JSON.createArrayNode();
        boolean already = false;
        if (Files.isRegularFile(file)) {
            JsonNode existing = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (existing != null && existing.isArray()) {
                for (JsonNode x : existing) {
                    all.add(x);
                    if (id.equals(x.path("id").asText())) already = true;
                }
            }
        }
        String text = YAML.writeValueAsString(JSON.createArrayNode().add(e));
        boolean written = false;
        if (!already && write) {
            all.add(e);
            Files.createDirectories(dir);
            Files.writeString(file, YAML.writeValueAsString(all), StandardCharsets.UTF_8);
            written = true;
        }
        return new Entry(file, text, written, already);
    }

    private static String mask(
            RunRecord run, Capture.Options options, Masker masker, String field, String value) {
        if (options.denyFields().contains(field)) return "‹denied›";
        return options.maskKeep().contains(field) ? value : masker.mask(value);
    }

    private static String shortHash(String id) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(id.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
