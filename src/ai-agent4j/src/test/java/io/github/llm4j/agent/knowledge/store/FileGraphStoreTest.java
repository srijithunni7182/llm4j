package io.github.llm4j.agent.knowledge.store;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.knowledge.model.Entity;
import io.github.llm4j.agent.knowledge.model.Relation;
import io.github.llm4j.agent.knowledge.model.Triple;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileGraphStoreTest {

    @TempDir Path dir;

    private static Entity e(String id, String type) {
        return Entity.builder().id(id).type(type).addProperty("label", id.toUpperCase()).build();
    }

    @Test
    void entitiesAndTriplesSurviveANewStore() {
        Path file = dir.resolve("g/graph.json");
        FileGraphStore g = new FileGraphStore(file);
        g.addTriple(new Triple(e("asha", "Person"), Relation.builder().type("WORKS_AT").addProperty("since", 2020).build(), e("acme", "Company")));
        g.addEntity(e("pune", "City"));

        FileGraphStore reopened = new FileGraphStore(file);
        assertEquals(3, reopened.getEntityCount());
        assertEquals(1, reopened.getTripleCount());
        Triple t = reopened.getTriples("asha").get(0);
        assertEquals("WORKS_AT", t.getPredicate().getType());
        assertEquals(2020, t.getPredicate().getProperty("since"));
        assertEquals("ACME", t.getObject().getProperty("label"));
        assertEquals(file, reopened.getFile());
    }

    @Test
    void clearIsPersisted() {
        Path file = dir.resolve("graph.json");
        FileGraphStore g = new FileGraphStore(file);
        g.addEntity(e("x", "Thing"));
        g.clear();
        assertEquals(0, new FileGraphStore(file).getEntityCount());
    }

    @Test
    void corruptOrInconsistentFilesAreRejected() throws Exception {
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "[1,2");
        assertThrows(IllegalStateException.class, () -> new FileGraphStore(bad));

        Path dangling = dir.resolve("dangling.json");
        Files.writeString(dangling, "{\"entities\":[],\"triples\":[{\"subject\":\"a\",\"predicate\":{\"type\":\"R\"},\"object\":\"b\"}]}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new FileGraphStore(dangling));
        assertTrue(e.getMessage().contains("isn't in the file"));

        Path noType = dir.resolve("notype.json");
        Files.writeString(noType, "{\"entities\":[{\"id\":\"a\"}]}");
        assertThrows(IllegalStateException.class, () -> new FileGraphStore(noType));
    }
}
