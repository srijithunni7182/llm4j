package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.Budget;
import io.github.llm4j.secret.InvokerCredentials;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The per-thread state of a run, and handing it to a worker thread. */
class ThreadScopeTest {

    private final ThreadScope scope = new ThreadScope();

    @Test
    @Tag("CC-V3.5")
    void aFreshThreadHasNoStepNoNamesNoBudgetsAndNoInvoker() {
        assertThat(scope.step.get()).isEmpty();
        assertThat(scope.locals.get()).isEmpty();
        assertThat(scope.budgets.get()).isEmpty();
        assertThat(scope.invoker.get()).isNull();
    }

    @Test
    @Tag("CC-V3.5")
    void aSnapshotCarriesEverythingToAnotherThreadAndTakesItAwayAgain() throws Exception {
        InvokerCredentials who = InvokerCredentials.of(Map.of("token", "t"));
        Map<String, Object> names = Map.of("item", 1);
        Budget budget = Budget.unlimited("step");
        scope.step.set("Main/s1");
        scope.locals.set(names);
        scope.budgets.set(List.of(budget));
        scope.invoker.set(who);
        ThreadScope.Snapshot snapshot = scope.capture();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Object[] seen = pool.submit(() -> {
                try (ThreadScope.Installed ignored = snapshot.install()) {
                    return new Object[] {scope.step.get(), scope.locals.get(), scope.budgets.get(), scope.invoker.get()};
                }
            }).get();
            Object[] after = pool.submit(() -> new Object[] {scope.step.get(), scope.locals.get(), scope.invoker.get(), scope.budgets.get()}).get();

            assertThat(seen[0]).isEqualTo("Main/s1");
            assertThat(seen[1]).isSameAs(names);
            assertThat(seen[2]).isEqualTo(List.of(budget));
            assertThat(seen[3]).isSameAs(who);
            assertThat(after[0]).isEqualTo("");
            assertThat((Map<?, ?>) after[1]).isEmpty();
            assertThat(after[2]).isNull();
            assertThat((List<?>) after[3]).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Tag("CC-V3.5")
    void aBranchStartsAtItsOwnStepWithItsOwnNamesButTheSameInvoker() throws Exception {
        InvokerCredentials who = InvokerCredentials.none();
        scope.invoker.set(who);
        scope.step.set("Main/s0");
        ThreadScope.Snapshot branch = scope.capture().at("Main/s0/p1", Map.of("n", 2));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Object[] seen = pool.submit(() -> {
                try (ThreadScope.Installed ignored = branch.install()) {
                    return new Object[] {scope.step.get(), scope.locals.get(), scope.invoker.get()};
                }
            }).get();

            assertThat(seen[0]).isEqualTo("Main/s0/p1");
            assertThat(seen[1]).isEqualTo(Map.of("n", 2));
            assertThat(seen[2]).isSameAs(who);
            assertThat(scope.step.get()).isEqualTo("Main/s0"); // the capturing thread is untouched
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Tag("CC-V3.4")
    void aRunWithoutAnInvokerLeavesNoneOnAThreadThatServedARunWithOne() throws Exception {
        InvokerCredentials who = InvokerCredentials.of(Map.of("token", "t"));
        scope.invoker.set(who);
        ThreadScope.Snapshot withInvoker = scope.capture();
        scope.invoker.remove();
        ThreadScope.Snapshot without = scope.capture();
        ExecutorService pool = Executors.newSingleThreadExecutor(); // one thread, reused, like the branch pool
        try {
            pool.submit(() -> {
                try (ThreadScope.Installed ignored = withInvoker.install()) {
                    return scope.invoker.get();
                }
            }).get();

            Object next = pool.submit(() -> {
                try (ThreadScope.Installed ignored = without.install()) {
                    return scope.invoker.get();
                }
            }).get();

            assertThat(next).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Tag("CC-V3.4")
    void installingAStateWithoutAnInvokerRemovesOneLeftOnTheThread() {
        ThreadScope.Snapshot without = scope.capture();
        scope.invoker.set(InvokerCredentials.of(Map.of("token", "stale")));

        try (ThreadScope.Installed ignored = without.install()) {
            assertThat(scope.invoker.get()).isNull();
        }
    }

    @Test
    @Tag("CC-V3.4")
    void clearRemovesEverythingFromTheCurrentThread() {
        scope.step.set("x");
        scope.locals.set(Map.of("a", 1));
        scope.budgets.set(List.of(Budget.unlimited("loop")));
        scope.invoker.set(InvokerCredentials.none());

        scope.clear();

        assertThat(scope.step.get()).isEmpty();
        assertThat(scope.locals.get()).isEmpty();
        assertThat(scope.budgets.get()).isEmpty();
        assertThat(scope.invoker.get()).isNull();
    }
}
