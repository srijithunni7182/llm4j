package io.github.llm4j.eval.dataset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
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
            Path temp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
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
            return YAML_MAPPER.readValue(yaml, listType);
        } catch (IOException e) {
            throw new EvalDatasetException("Failed to parse golden dataset YAML", e);
        }
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
