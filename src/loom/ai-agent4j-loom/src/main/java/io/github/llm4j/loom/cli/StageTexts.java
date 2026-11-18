package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The fixed texts of each journey stage: what it protects against, what to look at, the person's
 * decision and what a skip loses. They are facts the Guide words for a project, kept in one
 * resource so that a test can hold every stage to them.
 */
record StageTexts(String why, String see, String decision, String missing) {

    private static final String RESOURCE = "/llm4j-guide/stages.yaml";

    StageTexts {
        Objects.requireNonNull(why, "why");
        Objects.requireNonNull(see, "see");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(missing, "missing");
    }

    /** The texts of one stage; a stage with no texts is a programming error, not a user's. */
    static StageTexts of(String stageId) {
        StageTexts t = Holder.ALL.get(stageId);
        if (t == null) throw new IllegalStateException("No texts for journey stage " + stageId + " in " + RESOURCE);
        return t;
    }

    /** Every stage id that has texts, in the order of the resource. */
    static Map<String, StageTexts> all() {
        return Holder.ALL;
    }

    /** Loaded on first use, never when the class that names it is loaded. */
    private static final class Holder {
        static final Map<String, StageTexts> ALL = load();
    }

    private static Map<String, StageTexts> load() {
        try (InputStream in = StageTexts.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the jar");
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in);
            Map<String, StageTexts> out = new LinkedHashMap<>();
            root.fields()
                    .forEachRemaining(
                            e ->
                                    out.put(
                                            e.getKey(),
                                            new StageTexts(
                                                    text(e.getValue(), "why"),
                                                    text(e.getValue(), "see"),
                                                    text(e.getValue(), "decision"),
                                                    text(e.getValue(), "missing"))));
            return java.util.Collections.unmodifiableMap(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    private static String text(JsonNode stage, String field) {
        JsonNode v = stage.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
