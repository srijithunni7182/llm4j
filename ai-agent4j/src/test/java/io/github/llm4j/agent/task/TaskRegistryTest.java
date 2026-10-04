package io.github.llm4j.agent.task;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRegistryTest {

    @Test
    void registerAndGet() {
        TaskRegistry r = new TaskRegistry();
        assertTrue(r.isEmpty());
        Task b = Task.pure("B", c -> TaskResult.ok());
        Task a = Task.pure("A", c -> TaskResult.ok());
        assertSame(r, r.register(b).register(a));
        assertSame(a, r.get("A"));
        assertTrue(r.contains("B"));
        assertFalse(r.contains("C"));
        assertNull(r.get("C"));
        assertNull(r.get(null));
        assertEquals(List.of("A", "B"), List.copyOf(r.names()), "sorted");
        assertEquals(List.of(a, b), r.all());
        assertThrows(UnsupportedOperationException.class, () -> r.names().add("x"));
    }

    @Test
    void duplicateIsAnError() {
        TaskRegistry r = new TaskRegistry().register(Task.pure("A", c -> TaskResult.ok()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.register(Task.pure("A", c -> TaskResult.ok())));
        assertTrue(e.getMessage().contains("A"));
    }

    @Test
    void rejectsBadTasks() {
        TaskRegistry r = new TaskRegistry();
        assertThrows(NullPointerException.class, () -> r.register(null));
        Task unnamed = new Task() {
            @Override public String getName() { return "bad name"; }
            @Override public TaskResult run(TaskContext c) { return TaskResult.ok(); }
        };
        assertThrows(IllegalArgumentException.class, () -> r.register(unnamed));
    }

    @Test
    void discoveredFindsServices() throws Exception {
        TaskRegistry r = TaskRegistry.discovered();
        assertTrue(r.contains("DiscoveredTask"), r.names().toString());
        assertEquals("found", r.get("DiscoveredTask").run(TaskContext.of(java.util.Map.of(), java.util.Map.of())).value());
        // an empty class loader finds nothing and is not an error
        assertTrue(TaskRegistry.discovered(new ClassLoader(null) { }).isEmpty());
    }

    @Test
    void concurrentRegistrationOfOneNameHasOneWinner() throws Exception {
        TaskRegistry r = new TaskRegistry();
        int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var wins = new java.util.concurrent.atomic.AtomicInteger();
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                try { r.register(Task.pure("Same", c -> TaskResult.ok())); wins.incrementAndGet(); } catch (IllegalArgumentException ignored) { }
                return null;
            }));
        }
        start.countDown();
        for (var f : fs) f.get();
        pool.shutdown();
        assertEquals(1, wins.get());
    }
}
