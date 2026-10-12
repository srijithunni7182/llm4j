package io.github.llm4j.loom.inspect;

import io.github.llm4j.loom.execution.RunControl;
import io.github.llm4j.loom.inspect.control.Controls;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.RunState;
import io.github.llm4j.loom.observe.ObservableRun;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The page's controls for runs of this very process: a request goes straight to the run's {@link RunControl}. They are on only when the
 * app's secret store holds an admin password; without one the page says how to switch them on and offers none.
 */
final class EmbeddedControls implements Controls {

    /** The secret that holds the admin password. */
    static final String PASSWORD_SECRET = "LOOM_INSPECT_ADMIN_PASSWORD";

    private final RunRegistry registry;
    private final Map<String, ObservableRun> runs = new ConcurrentHashMap<>();
    private volatile ObservableRun latest;

    EmbeddedControls(RunRegistry registry) {
        this.registry = registry;
    }

    /** A run (or a resumed run, under a new attach) the controls reach. */
    void register(String traceId, ObservableRun run) {
        runs.put(traceId, run);
        latest = run;
    }

    /** The run has ended (or is suspended to resume under a new attach): nothing can be asked of it now. */
    void unregister(String traceId) {
        runs.remove(traceId);
    }

    /** The admin password, read from the app's own secret store each time, so rotating it takes effect at once. */
    String password() {
        ObservableRun run = latest;
        if (run == null || run.secretStore() == null) return null;
        try {
            return run.secretStore().resolve(PASSWORD_SECRET);
        } catch (RuntimeException notSet) {
            return null;
        }
    }

    @Override
    public boolean enabled() {
        String p = password();
        return p != null && !p.isBlank();
    }

    @Override
    public String disabledReason() {
        return "set " + PASSWORD_SECRET + " in this app's secret store to enable pause, suspend and stop (weave secrets set " + PASSWORD_SECRET + ")";
    }

    @Override
    public Set<Action> allowed(String traceId) {
        return enabled() ? possible(traceId) : EnumSet.noneOf(Action.class);
    }

    /** What the run itself can do now, whether or not the page's password is set (Fabric commands arrive without it). */
    Set<Action> possible(String traceId) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        ObservableRun run = runs.get(traceId);
        if (run == null) return out;
        RunState state = registry.findByTrace(traceId).orElse(null);
        if (state != null) {
            RunState.Status st = state.status();
            if (st == RunState.Status.FINISHED || st == RunState.Status.FAILED || st == RunState.Status.STOPPED) return out;
        }
        RunControl.State c = run.control().state();
        out.add(Action.STOP);
        if (run.journal() != null) out.add(Action.SUSPEND); // suspending needs a journal to resume from
        if (!c.paused()) out.add(Action.PAUSE);
        if (c.paused() || c.pending() != null) out.add(Action.RESUME);
        return out;
    }

    @Override
    public Outcome request(String traceId, Action action, String who) {
        if (!enabled()) return Outcome.refused(503, disabledReason());
        return perform(traceId, action, who);
    }

    /** Carries the request out for a caller already authorised (the signed Fabric channel, or the page after its sign-in). */
    Outcome perform(String traceId, Action action, String who) {
        if (!possible(traceId).contains(action)) return Outcome.refused(409, "'" + action.word() + "' is not available for this run now" + (action == Action.SUSPEND ? " (a run without a journal cannot be suspended)" : ""));
        ObservableRun run = runs.get(traceId);
        run.control().request(RunControl.Action.valueOf(action.name()), who);
        return Outcome.taken(switch (action) {
            case PAUSE -> "pause requested: the run holds before its next step (a step already running finishes first)";
            case RESUME -> "resume requested";
            case SUSPEND -> "suspend requested: the run ends cleanly at its next step and can be resumed with the same journal";
            case STOP -> "stop requested: the run ends at its next step (a step already running finishes first)";
        });
    }

    /** A command from Fabric: the server has already authenticated and queued it; the run decides whether it still fits. */
    io.github.llm4j.loom.inspect.wire.CommandResult execute(io.github.llm4j.loom.inspect.wire.Command c) {
        Action action = Action.of(c.action());
        if (action == null) return new io.github.llm4j.loom.inspect.wire.CommandResult(c.id(), false, "unknown action '" + c.action() + "'");
        Outcome o = perform(c.trace(), action, c.by() == null ? "fabric" : c.by());
        return new io.github.llm4j.loom.inspect.wire.CommandResult(c.id(), o.accepted(), o.message());
    }
}
