package io.github.llm4j.agent.task;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskContextTest {

    private static TaskContext ctx(Map<String, ?> args, Map<String, ?> vars) {
        return TaskContext.of(args, vars, "Main/s1", "key-1");
    }

    @Test
    void argsVariablesAndIdentity() {
        TaskContext c = ctx(Map.of("amount", 40), Map.of("request", Map.of("order_id", "A-1")));
        assertEquals(40, c.arg("amount"));
        assertNull(c.arg("missing"));
        assertEquals("Main/s1", c.stepId());
        assertEquals("key-1", c.idempotencyKey());
        assertEquals("", TaskContext.of(Map.of(), Map.of()).stepId());
        assertEquals("", TaskContext.of(null, null).idempotencyKey());
        assertTrue(TaskContext.of(null, null).args().isEmpty());
    }

    @Test
    void variablePaths() {
        Map<String, Object> vars = Map.of(
                "request", Map.of("order_id", "A-1", "lines", List.of(Map.of("sku", "S1"), Map.of("sku", "S2"))),
                "name", "Ada");
        TaskContext c = ctx(Map.of(), vars);
        assertEquals("A-1", c.variable("request.order_id"));
        assertEquals("S2", c.variable("request.lines.1.sku"));
        assertEquals("Ada", c.variable("name"));
        assertNull(c.variable("request.nothing"));
        assertNull(c.variable("request.lines.9.sku"));
        assertNull(c.variable("name.length"));
        assertNull(c.variable("absent.deeper"));
        assertEquals("A-1", c.variable("request.order_id", String.class));
        assertNull(c.variable("nope", String.class));
    }

    @Test
    void typedAccessors() {
        TaskContext c = ctx(Map.of("i", 7, "d", 2.5, "s", "12.50", "n", "abc", "t", "TRUE", "big", 1_000_000_000_000L), Map.of());
        assertEquals(7, c.arg("i", Integer.class));
        assertEquals(7L, c.arg("i", Long.class));
        assertEquals(7.0, c.arg("i", Double.class));
        assertEquals(new BigDecimal("12.50"), c.arg("s", BigDecimal.class));
        assertEquals(12.5, c.arg("s", Double.class));
        assertEquals("2.5", c.arg("d", String.class));
        assertTrue(c.arg("t", Boolean.class));
        assertNull(c.arg("absent", Integer.class));
        assertThrows(TaskNotPerformed.class, () -> c.arg("n", Integer.class));
        assertThrows(TaskNotPerformed.class, () -> c.arg("d", Integer.class), "2.5 is not an exact int");
        assertThrows(TaskNotPerformed.class, () -> c.arg("big", Integer.class), "out of int range");
        assertThrows(TaskNotPerformed.class, () -> c.arg("s", Boolean.class));
        assertThrows(TaskNotPerformed.class, () -> c.arg("i", java.util.List.class));
        TaskNotPerformed e = assertThrows(TaskNotPerformed.class, () -> c.arg("n", Integer.class));
        assertTrue(e.getMessage().contains("argument \"n\""), e.getMessage());
    }

    @Test
    void requireArg() {
        TaskContext c = ctx(Map.of("a", 1), Map.of());
        assertEquals(1, c.requireArg("a"));
        assertEquals(1L, c.requireArg("a", Long.class));
        TaskNotPerformed e = assertThrows(TaskNotPerformed.class, () -> c.requireArg("b"));
        assertTrue(e.getMessage().contains("\"b\" is required"));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("z", null);
        assertThrows(TaskNotPerformed.class, () -> ctx(withNull, Map.of()).requireArg("z"));
    }

    @Test
    void immutable() {
        Map<String, Object> inner = new LinkedHashMap<>(Map.of("k", "v"));
        List<Object> list = new ArrayList<>(List.of("a"));
        Map<String, Object> vars = new HashMap<>();
        vars.put("m", inner);
        vars.put("l", list);
        Map<String, Object> args = new HashMap<>(Map.of("m", inner));
        TaskContext c = ctx(args, vars);

        assertThrows(UnsupportedOperationException.class, () -> c.args().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> c.variables().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) c.variable("m")).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) c.variable("l")).add("b"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) c.arg("m")).clear());

        // and the source is not aliased: changing it afterwards does not change what the task sees
        inner.put("late", "change");
        list.add("late");
        args.put("late", 1);
        assertEquals(Map.of("k", "v"), c.variable("m"));
        assertEquals(List.of("a"), c.variable("l"));
        assertNull(c.arg("late"));
    }

    @Test
    void cyclesAreRejectedNotLooped() {
        Map<String, Object> self = new HashMap<>();
        self.put("me", self);
        assertThrows(IllegalArgumentException.class, () -> ctx(Map.of(), Map.of("loop", self)));
    }
}
