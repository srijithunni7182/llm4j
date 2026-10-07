package io.github.llm4j.eval.dataset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads a golden dataset — a list of {@link EvalScenario} rows — from a YAML file, so a suite of
 * eval cases can be authored and reviewed as data instead of Java code. See {@link EvalScenario}
 * for the expected shape; a minimal file looks like:
 *
 * <pre>{@code
 * - name: percentage-calculation
 *   input: "What is 15% of 240?"
 *   expectedOutputContains: "36"
 *   expectedTools: [calculator]
 * - name: capital-lookup
 *   input: "What's the capital of France?"
 *   expectedOutputContains: "Paris"
 * }</pre>
 */
public final class EvalScenarios {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** Writer-side mapper: omits nulls, no {@code ---} marker, block style for multi-line text. */
    private static final ObjectMapper YAML_WRITER =
            new ObjectMapper(
                            new YAMLFactory()
                                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                                    .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE))
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private EvalScenarios() {}

    /**
     * Serializes scenarios to the YAML shape {@link #fromYaml(Path)} reads, with a stable field
     * order so committed datasets diff cleanly. Null fields are omitted.
     */
    public static String toYaml(List<EvalScenario> scenarios) {
        try {
            return YAML_WRITER.writeValueAsString(scenarios);
        } catch (IOException e) {
            throw new EvalDatasetException("Failed to serialize golden dataset to YAML", e);
        }
    }

    /** Writes the dataset atomically (temp file then move), creating parent directories. */
    public static void toYaml(List<EvalScenario> scenarios, Path yamlFile) {
        byte[] content = toYaml(scenarios).getBytes(StandardCharsets.UTF_8);
        try {
            Path absolute = yamlFile.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            Path temp =
                    Files.createTempFile(
                            absolute.getParent(), absolute.getFileName().toString(), ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new EvalDatasetException("Failed to write golden dataset to " + yamlFile, e);
        }
    }

    public static List<EvalScenario> fromYaml(Path yamlFile) {
        try {
            return fromYaml(Files.newInputStream(yamlFile));
        } catch (IOException e) {
            throw new EvalDatasetException("Failed to read golden dataset from " + yamlFile, e);
        }
    }

    /**
     * Parses a golden dataset from {@code yaml}. Jackson closes the stream itself once it has
     * finished reading (its default {@code AUTO_CLOSE_SOURCE} behavior), so callers don't need to.
     */
    public static List<EvalScenario> fromYaml(InputStream yaml) {
        try {
            CollectionType listType =
                    YAML_MAPPER
                            .getTypeFactory()
                            .constructCollectionType(List.class, EvalScenario.class);
            List<EvalScenario> read = YAML_MAPPER.readValue(yaml, listType);
            return read == null ? List.of() : List.copyOf(read);
        } catch (IOException e) {
            throw new EvalDatasetException("Failed to parse golden dataset YAML", e);
        }
    }

    /**
     * Reads {@code RUBRIC:} and {@code EXPECT:} lines in {@code context} as the {@code rubric} and {@code expect} fields, the way datasets were
     * written before those fields existed. Only dataset folders ({@link EvalDataset}) are read this way; {@code fromYaml} returns {@code context} exactly as written. Lines already in the fields come first; the lines are removed from {@code context}.
     */
    static EvalScenario normalized(EvalScenario s) {
        if (s.context() == null || s.context().stream().noneMatch(c -> c.startsWith("RUBRIC:") || c.startsWith("EXPECT:"))) {
            return s;
        }
        List<String> context = new java.util.ArrayList<>();
        List<String> rubric = new java.util.ArrayList<>(s.rubricLines());
        List<String> expect = new java.util.ArrayList<>(s.expectLines());
        for (String line : s.context()) {
            if (line.startsWith("RUBRIC:")) rubric.add(line.substring("RUBRIC:".length()).strip());
            else if (line.startsWith("EXPECT:")) expect.add(line.substring("EXPECT:".length()).strip());
            else context.add(line);
        }
        return new EvalScenario(s.name(), s.input(), s.expectedOutputContains(), s.expectedOutput(), s.expectedTools(),
                context.isEmpty() ? null : context, s.retrievalContext(), s.id(), s.dimensions(), s.tags(),
                rubric.isEmpty() ? null : rubric, expect.isEmpty() ? null : expect, s.inputs());
    }

    /**
     * Loads every dataset file in a folder, by file name without {@code .yaml}: {@code researcher.yaml} is {@code researcher}. The files
     * {@code dataset.yaml} and {@code fixtures.yaml} are not scenario lists and are skipped. Use {@link EvalDataset#load(Path)} to also get
     * the dimensions and what is wrong with the folder.
     */
    public static java.util.Map<String, List<EvalScenario>> fromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new EvalDatasetException("Golden dataset folder not found: " + dir);
        }
        EvalDataset dataset = EvalDataset.load(dir);
        var unreadable = dataset.problems().stream().filter(EvalDataset.Problem::fatal).findFirst();
        if (unreadable.isPresent()) {
            throw new EvalDatasetException(unreadable.get().toString());
        }
        return dataset.files();
    }

    /**
     * Loads a golden dataset from a classpath resource, e.g. a file at {@code
     * src/test/resources/scenarios.yaml} loaded as {@code fromYamlResource("scenarios.yaml")}.
     */
    public static List<EvalScenario> fromYamlResource(String resourcePath) {
        InputStream in = EvalScenarios.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new EvalDatasetException(
                    "Golden dataset resource not found on classpath: " + resourcePath);
        }
        return fromYaml(in);
    }
}
