package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Guide's catalogue: the facts it may state about llm4j, each with what it does not do, and the
 * moments that bring a fact up. Both are resources, so a test holds every command, editor command
 * and documentation link in them to what exists.
 */
final class GuideCatalogue {

    /** Where the documentation lives online, for a reader who has no checkout of the repository. */
    static final String DOCS_BASE = "https://github.com/srijithunni7182/llm4j/blob/main/";

    /** One fact about a feature. {@code command} and {@code editor} are empty when there is none. */
    record Fact(String id, String title, String purpose, String solves, String command, String editor, String not, String doc) {
        Fact {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(solves, "solves");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(editor, "editor");
            Objects.requireNonNull(not, "not");
            Objects.requireNonNull(doc, "doc");
        }

        /** The documentation file, without the heading. */
        String docFile() {
            int hash = doc.indexOf('#');
            return hash < 0 ? doc : doc.substring(0, hash);
        }

        /** The heading the link points at, or empty. */
        String docHeading() {
            int hash = doc.indexOf('#');
            return hash < 0 ? "" : doc.substring(hash + 1);
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("for", purpose);
            m.put("solves", solves);
            m.put("command", command);
            m.put("editor", editor);
            m.put("not", not);
            m.put("doc", DOCS_BASE + docFile() + (docHeading().isEmpty() ? "" : "  (section: " + docHeading() + ")"));
            return m;
        }
    }

    /** One moment: a project trigger (or empty), words in a question, the fact, and how to show it. */
    record Moment(String id, String project, List<String> words, String fact, String show) {
        Moment {
            words = List.copyOf(words);
        }

        Map<String, Object> json(Fact f) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("fact", fact);
            m.put("title", f == null ? fact : f.title());
            m.put("show", show);
            if (!words.isEmpty()) m.put("words", words);
            return m;
        }
    }

    private GuideCatalogue() {}

    static Map<String, Fact> facts() {
        return Holder.FACTS;
    }

    static List<Moment> moments() {
        return Holder.MOMENTS;
    }

    /** Loaded on first use. */
    private static final class Holder {
        static final Map<String, Fact> FACTS = loadFacts();
        static final List<Moment> MOMENTS = loadMoments();
    }

    private static JsonNode yaml(String name) {
        String resource = "/llm4j-guide/" + name;
        try (InputStream in = GuideCatalogue.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException(resource + " is missing from the jar");
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    private static Map<String, Fact> loadFacts() {
        Map<String, Fact> out = new LinkedHashMap<>();
        yaml("facts.yaml")
                .fields()
                .forEachRemaining(
                        e -> {
                            JsonNode f = e.getValue();
                            out.put(
                                    e.getKey(),
                                    new Fact(
                                            e.getKey(),
                                            f.path("title").asText(""),
                                            f.path("for").asText(""),
                                            f.path("solves").asText(""),
                                            f.path("command").asText(""),
                                            f.path("editor").asText(""),
                                            f.path("not").asText(""),
                                            f.path("doc").asText("")));
                        });
        return Collections.unmodifiableMap(out);
    }

    private static List<Moment> loadMoments() {
        List<Moment> out = new ArrayList<>();
        yaml("moments.yaml")
                .fields()
                .forEachRemaining(
                        e -> {
                            JsonNode m = e.getValue();
                            List<String> words = new ArrayList<>();
                            m.path("words").forEach(w -> words.add(w.asText()));
                            out.add(
                                    new Moment(
                                            e.getKey(),
                                            m.path("project").asText(""),
                                            words,
                                            m.path("fact").asText(""),
                                            m.path("show").asText("")));
                        });
        return List.copyOf(out);
    }
}
