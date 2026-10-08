package io.github.llm4j.agent.task;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskResultTest {

    @Test
    void shapes() {
        assertEquals(Map.of("outcome", "ok"), TaskResult.ok().toMap());
        assertEquals(Map.of("outcome", "ok", "value", 5), TaskResult.value(5).toMap());
        assertEquals(Map.of("outcome", "rejected", "reason", "over limit"), TaskResult.rejected("over limit").toMap());
        assertEquals(Map.of("outcome", "needs_review"), TaskResult.outcome("needs_review").toMap());
        assertEquals(Map.of("outcome", "ok", "a", 1, "b", "x"), TaskResult.ok(Map.of("a", 1, "b", "x")).toMap());
        TaskResult r = TaskResult.outcome("approved").reason("within policy").with("amount", 40).withValue(true);
        assertEquals("approved", r.outcome());
        assertEquals("within policy", r.reason());
        assertTrue(r.hasValue());
        assertEquals(true, r.value());
        assertEquals(Map.of("amount", 40), r.data());
        assertEquals(List.of("outcome", "reason", "value", "amount"), new ArrayList<>(r.toMap().keySet()));
    }

    @Test
    void nullValueIsStillAValue() {
        TaskResult r = TaskResult.value(null);
        assertTrue(r.hasValue());
        assertNull(r.value());
        assertTrue(r.toMap().containsKey("value"));
        assertFalse(TaskResult.ok().hasValue());
    }

    @Test
    void immutableAndCopying() {
        TaskResult base = TaskResult.ok();
        TaskResult more = base.with("k", 1);
        assertTrue(base.data().isEmpty(), "with() returns a new result");
        assertEquals(1, more.data().size());
        assertThrows(UnsupportedOperationException.class, () -> more.toMap().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> more.data().put("x", 1));

        Map<String, Object> source = new LinkedHashMap<>(Map.of("n", 1));
        List<Object> list = new ArrayList<>(List.of(1));
        TaskResult r = TaskResult.ok().with("m", source).with("l", list);
        source.put("late", 2);
        list.add(2);
        assertEquals(Map.of("n", 1), r.toMap().get("m"));
        assertEquals(List.of(1), r.toMap().get("l"));
    }

    @Test
    void outcomeNaming() {
        for (String ok : new String[] {"ok", "approved", "needs_review", "a1"}) assertEquals(ok, TaskResult.outcome(ok).outcome());
        for (String bad : new String[] {"", "Ok", "1x", "needs review", "x-y", "_x"}) {
            assertThrows(IllegalArgumentException.class, () -> TaskResult.outcome(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> TaskResult.outcome(null));
    }

    @Test
    void reservedKeys() {
        for (String key : new String[] {"outcome", "reason", "value"}) {
            assertThrows(IllegalArgumentException.class, () -> TaskResult.ok().with(key, 1), key);
            assertThrows(IllegalArgumentException.class, () -> TaskResult.ok(Map.of(key, 1)), key);
        }
        assertThrows(IllegalArgumentException.class, () -> TaskResult.ok().with("", 1));
        assertThrows(IllegalArgumentException.class, () -> TaskResult.ok().with(null, 1));
    }

    @Test
    void jsonSafe() {
        TaskResult fine = TaskResult.ok()
                .with("s", "x").with("n", 1.5).with("b", false).with("z", null)
                .with("nested", Map.of("list", List.of(1, "two", Map.of("k", true))));
        assertEquals(5, fine.data().size());

        assertThrows(IllegalArgumentException.class, () -> TaskResult.ok().with("d", new Date()));
        assertThrows(IllegalArgumentException.class, () -> TaskResult.ok().with("o", new Object()));
        assertThrows(IllegalArgumentException.class, () -> TaskResult.value(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TaskResult.value(Double.POSITIVE_INFINITY));
        Map<Object, Object> intKeys = new HashMap<>();
        intKeys.put(1, "x");
        assertThrows(IllegalArgumentException.class, () -> TaskResult.value(intKeys));
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("me", cyclic);
        assertThrows(IllegalArgumentException.class, () -> TaskResult.value(cyclic));
        List<Object> cyc = new ArrayList<>();
        cyc.add(cyc);
        assertThrows(IllegalArgumentException.class, () -> TaskResult.value(cyc));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TaskResult.ok().with("when", new Date()));
        assertTrue(e.getMessage().contains("when"), e.getMessage());
        // the same instance twice (not a cycle) is fine
        List<Object> shared = List.of(1);
        assertDoesNotThrow(() -> TaskResult.value(List.of(shared, shared)));
    }

    @Test
    void equalsByContent() {
        assertEquals(TaskResult.rejected("x"), TaskResult.outcome("rejected").reason("x"));
        assertNotEquals(TaskResult.rejected("x"), TaskResult.rejected("y"));
        assertEquals(TaskResult.rejected("x").hashCode(), TaskResult.outcome("rejected").reason("x").hashCode());
    }
}
