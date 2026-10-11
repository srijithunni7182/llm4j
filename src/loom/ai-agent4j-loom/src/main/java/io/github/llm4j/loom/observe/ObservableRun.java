package io.github.llm4j.loom.observe;

import io.github.llm4j.budget.Budget;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.LevelStore;
import io.github.llm4j.loom.execution.SpendReport;
import io.github.llm4j.loom.execution.TraceListener;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.secret.SecretStore;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything an observer of a run may read, in one place, so an add-on such as loom-inspect depends on this interface and not on
 * the executor's internals. It is read-only by intent: the journal and the stores are the run's own objects, so look, do not write.
 *
 * <p>Listeners must be added <b>before</b> the executor is initialized; agents report events only when someone listens.
 */
public interface ObservableRun {

    /** The run id the executor was given, or null for a run with no journal. */
    String runId();

    /** Where the run's steps are recorded. */
    RunJournal journal();

    /** The parsed script: workflows, agents, tools and everything else it declares. */
    LoomScript script();

    /** The secret store the run uses, or null when it has none. */
    SecretStore secretStore();

    /** Where decisions keep their cases, when the run has an autonomy store. */
    Optional<Ledger> ledger();

    /** The level of each decision, when the run has an autonomy store. */
    Optional<LevelStore> levelStore();

    /** The run budget (limits and what has been spent), or null when the script sets none. */
    Budget runBudget();

    /** What the run has spent so far, per agent and per step. */
    SpendReport spend();

    /** A shallow copy of the workflow's variables now. The values are the run's own: read them, do not change them. */
    Map<String, Object> variables();

    /** The clock the run uses for budget windows, rate limits and sleeps. */
    Clock clock();

    /**
     * What an observer may do to the run: pause, resume, suspend, stop. Unlike everything else here this changes the run, so offer it only
     * behind a login. Takes effect between steps; see {@link io.github.llm4j.loom.execution.RunControl}.
     */
    io.github.llm4j.loom.execution.RunControl control();

    /** Receives the run's trace events live. Call before the executor is initialized. */
    void addTraceListener(TraceListener listener);

    /** Told whenever the run pauses (a limit, a sleep), before {@link RunSuspended} leaves {@code executeWorkflow}. */
    void addSuspensionListener(Consumer<RunSuspended> listener);
}
