package io.github.llm4j.agent.knowledge.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Relation;
import io.github.llm4j.agent.knowledge.model.Triple;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link InMemoryGraphStore} kept in a JSON file, so a graph survives restarts. Loaded when
 * constructed; rewritten (atomically) after every change.
 *
 * <p>File format: {@code {"entities": [{id, type, properties}], "triples": [{subject, predicate:
 * {type, properties}, object}]}}, where a triple's subject and object are entity ids.
 */
public class FileGraphStore extends InMemoryGraphStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private boolean saving = true;

    /**
     * @throws IllegalStateException if the file exists but isn't a graph this store wrote
     */
    public FileGraphStore(Path file) {
        this.file = file;
        if (Files.exists(file)) load();
    }

    public Path getFile() {
        return file;
    }

    @Override
    public synchronized void addEntity(Entity entity) {
        super.addEntity(entity);
        if (saving) save();
    }

    @Override
    public synchronized void addTriple(Triple triple) {
        boolean outer = saving;
        saving = false; // addTriple adds its entities too: save once
        try {
            super.addTriple(triple);
        } finally {
            saving = outer;
        }
        if (saving) save();
    }

    @Override
    public synchronized void clear() {
        super.clear();
        save();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        saving = false;
        try {
            Map<String, Object> root = MAPPER.readValue(file.toFile(), Map.class);
            List<Map<String, Object>> entities = (List<Map<String, Object>>) root.getOrDefault("entities", List.of());
            List<Map<String, Object>> triples = (List<Map<String, Object>>) root.getOrDefault("triples", List.of());
            for (Map<String, Object> e : entities) addEntity(entity(e));
            for (Map<String, Object> t : triples) {
                Entity subject = getEntity(String.valueOf(t.get("subject")));
                Entity object = getEntity(String.valueOf(t.get("object")));
                if (subject == null || object == null) {
                    throw new IllegalStateException("a triple refers to an entity that isn't in the file: " + t);
                }
                Map<String, Object> p = (Map<String, Object>) t.get("predicate");
                Relation relation = Relation.builder()
                        .type(String.valueOf(p.get("type")))
                        .properties(p.get("properties") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of())
                        .build();
                addTriple(new Triple(subject, relation, object));
            }
        } catch (IOException | ClassCastException | NullPointerException e) {
            throw new IllegalStateException("Can't read graph " + file + ": " + e.getMessage(), e);
        } finally {
            saving = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static Entity entity(Map<String, Object> e) {
        Object id = e.get("id");
        Object type = e.get("type");
        if (id == null || type == null) throw new IllegalStateException("an entity needs id and type: " + e);
        return Entity.builder()
                .id(String.valueOf(id))
                .type(String.valueOf(type))
                .properties(e.get("properties") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of())
                .build();
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> entities = new ArrayList<>();
        for (Entity e : getAllEntities()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("type", e.getType());
            m.put("properties", e.getProperties());
            entities.add(m);
        }
        List<Map<String, Object>> triples = new ArrayList<>();
        for (Triple t : getAllTriples()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", t.getSubject().getId());
            m.put("predicate", Map.of("type", t.getPredicate().getType(), "properties", t.getPredicate().getProperties()));
            m.put("object", t.getObject().getId());
            triples.add(m);
        }
        root.put("entities", entities);
        root.put("triples", triples);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't write graph " + file + ": " + e.getMessage(), e);
        }
    }
}
