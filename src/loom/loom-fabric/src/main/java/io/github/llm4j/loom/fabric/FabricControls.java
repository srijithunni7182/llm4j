package io.github.llm4j.loom.fabric;

import io.github.llm4j.loom.inspect.control.Controls;
import io.github.llm4j.loom.inspect.receive.CommandQueue;
import io.github.llm4j.loom.inspect.receive.Receiver;
import io.github.llm4j.loom.inspect.state.RunState;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The page's controls for runs that live in other processes: a request becomes a signed command the owning process collects on its
 * long-poll and carries out at the run's next step. The server decides what to show from what the process has reported; the process
 * has the last word and refuses what no longer fits.
 */
final class FabricControls implements Controls {

    private final Receiver receiver;
    private final CommandQueue queue;
    private final Supplier<String> adminPassword;

    FabricControls(Receiver receiver, CommandQueue queue, Supplier<String> adminPassword) {
        this.receiver = receiver;
        this.queue = queue;
        this.adminPassword = adminPassword;
    }

    @Override
    public boolean enabled() {
        String p = adminPassword.get();
        return p != null && !p.isBlank();
    }

    @Override
    public String disabledReason() {
        return "set the admin password with 'fabric admin set-password' to enable pause, suspend and stop";
    }

    @Override
    public Set<Action> allowed(String traceId) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        RunState run = receiver.registry().findByTrace(traceId).orElse(null);
        if (run == null || !enabled() || !receiver.online(run.client())) return out;
        RunState.Status st = run.status();
        if (st == RunState.Status.FINISHED || st == RunState.Status.FAILED || st == RunState.Status.STOPPED) return out;
        Object control = run.view().get("control");
        boolean held = control instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("held"));
        boolean pending = control instanceof Map<?, ?> m && m.get("pending") != null;
        out.add(Action.STOP);
        out.add(Action.SUSPEND); // the process refuses when the run has no journal
        if (!held) out.add(Action.PAUSE);
        if (held || pending) out.add(Action.RESUME);
        return out;
    }

    @Override
    public Outcome request(String traceId, Action action, String who) {
        if (!enabled()) return Outcome.refused(503, disabledReason());
        RunState run = receiver.registry().findByTrace(traceId).orElse(null);
        if (run == null) return Outcome.refused(404, "unknown run");
        if (!receiver.online(run.client())) return Outcome.refused(409, run.client() + " is offline; nothing can be sent to it");
        if (!allowed(traceId).contains(action)) return Outcome.refused(409, "'" + action.word() + "' is not available for this run now");
        queue.offer(run.client(), traceId, action.word(), who);
        return Outcome.taken(action.word() + " sent to " + run.client() + ": it takes effect at the run's next step (a step already running finishes first). The run shows it when the process confirms.");
    }
}
