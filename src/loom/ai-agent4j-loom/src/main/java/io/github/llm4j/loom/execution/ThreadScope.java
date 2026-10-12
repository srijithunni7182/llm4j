package io.github.llm4j.loom.execution;

import io.github.llm4j.budget.Budget;
import io.github.llm4j.secret.InvokerCredentials;
import java.util.List;
import java.util.Map;

/**
 * The state a run keeps per thread, and the one way to hand it to another thread. A step that moves work to a worker thread (a parallel
 * branch, a broadcast, a step with a timeout) takes a {@link Snapshot} on its own thread and installs it on the worker's; the worker removes
 * it when it is done, so a pooled thread never carries one run's state into the next.
 */
final class ThreadScope {

    final ThreadLocal<String> step = ThreadLocal.withInitial(() -> "");
    /** Block-local names (a for-each item, _error) visible to the statements of that block. */
    final ThreadLocal<Map<String, Object>> locals = ThreadLocal.withInitial(Map::of);
    /** Step, loop and for-each budgets enclosing the statement this thread is running. */
    final ThreadLocal<List<Budget>> budgets = ThreadLocal.withInitial(List::of);
    /** Whoever the run is for; null when it has no invoker. */
    final ThreadLocal<InvokerCredentials> invoker = new ThreadLocal<>();

    /** What this thread's run state is right now. */
    Snapshot capture() {
        return new Snapshot(step.get(), locals.get(), budgets.get(), invoker.get());
    }

    /** An immutable copy of one thread's state. */
    final class Snapshot {
        private final String step;
        private final Map<String, Object> locals;
        private final List<Budget> budgets;
        private final InvokerCredentials invoker;

        private Snapshot(String step, Map<String, Object> locals, List<Budget> budgets, InvokerCredentials invoker) {
            this.step = step;
            this.locals = locals;
            this.budgets = budgets;
            this.invoker = invoker;
        }

        /** The same state with another step id and block-local names (a branch that starts at its own place in the script). */
        Snapshot at(String stepId, Map<String, Object> names) {
            return new Snapshot(stepId, names, budgets, invoker);
        }

        /** Sets this state on the current thread. Close the result to remove it. */
        Installed install() {
            ThreadScope.this.step.set(step);
            ThreadScope.this.locals.set(locals);
            ThreadScope.this.budgets.set(budgets);
            if (invoker != null) ThreadScope.this.invoker.set(invoker);
            return ThreadScope.this::clear;
        }
    }

    /** A state set on a thread; closing it removes the state from that thread. */
    @FunctionalInterface
    interface Installed extends AutoCloseable {
        @Override
        void close();
    }

    /** Removes everything from the current thread. */
    void clear() {
        step.remove();
        locals.remove();
        budgets.remove();
        invoker.remove();
    }
}
