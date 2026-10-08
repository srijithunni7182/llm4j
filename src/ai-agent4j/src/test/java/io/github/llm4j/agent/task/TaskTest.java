package io.github.llm4j.agent.task;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.tool.EffectPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskTest {

    private static final TaskContext EMPTY = TaskContext.of(Map.of(), Map.of());

    @Test
    void nameRule() {
        for (String ok : new String[] {"A", "RefundPolicy", "issue_refund", "issue-refund", "T2"}) {
            assertTrue(Task.isValidName(ok), ok);
            assertEquals(ok, Task.pure(ok, c -> TaskResult.ok()).getName());
        }
        for (String bad : new String[] {"", " ", "1Task", "_x", "-x", "has space", "dot.ted", "a{b}", "é"}) {
            assertFalse(Task.isValidName(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> Task.pure(bad, c -> TaskResult.ok()), bad);
        }
        assertFalse(Task.isValidName(null));
        assertThrows(IllegalArgumentException.class, () -> Task.pure(null, c -> TaskResult.ok()));
    }

    @Test
    void defaults() throws Exception {
        Task t = new Task() {
            @Override public String getName() { return "T"; }
            @Override public TaskResult run(TaskContext c) { return TaskResult.ok(); }
        };
        assertEquals("", t.getDescription());
        assertEquals(EffectPolicy.DEFAULT, t.policy());
        assertFalse(t.requiresApproval(Map.of("amount", 1)));
        assertEquals("ok", t.run(EMPTY).outcome());
    }

    @Test
    void defaultEffectIsChanges() {
        Task t = new Task() {
            @Override public String getName() { return "T"; }
            @Override public TaskResult run(TaskContext c) { return TaskResult.ok(); }
        };
        assertEquals(TaskEffect.CHANGES, t.effect());
    }

    @Test
    void factories() throws Exception {
        Task pure = Task.pure("P", c -> TaskResult.value(c.requireArg("x", Integer.class) + 1));
        Task reads = Task.reads("R", c -> TaskResult.ok());
        EffectPolicy idem = new EffectPolicy(EffectPolicy.OnUnknown.RETRY, true, 3);
        Task changes = Task.changes("C", idem, c -> TaskResult.ok());
        Task custom = Task.of("X", TaskEffect.READS, EffectPolicy.DEFAULT, c -> TaskResult.ok());

        assertEquals(TaskEffect.NONE, pure.effect());
        assertEquals(TaskEffect.READS, reads.effect());
        assertEquals(TaskEffect.CHANGES, changes.effect());
        assertEquals(idem, changes.policy());
        assertEquals(TaskEffect.READS, custom.effect());
        assertEquals(6, pure.run(TaskContext.of(Map.of("x", 5), Map.of())).value());
        assertThrows(NullPointerException.class, () -> Task.changes("C", null, c -> TaskResult.ok()));
        assertThrows(NullPointerException.class, () -> Task.pure("P", null));
        assertTrue(pure.toString().contains("NONE"));
    }

    @Test
    void aTaskMayThrowChecked() {
        Task t = Task.pure("T", c -> { throw new java.io.IOException("disk"); });
        assertThrows(java.io.IOException.class, () -> t.run(EMPTY));
    }
}
