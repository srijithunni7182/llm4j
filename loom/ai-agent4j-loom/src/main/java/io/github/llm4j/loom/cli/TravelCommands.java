package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.RunTravel;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code weave timeline|rewind|reset|fork}: look at a run's history, go back, clear it, or branch a copy off. They only touch the run's journal. */
final class TravelCommands {

    private TravelCommands() { }

    static final String JOURNAL = "journal.json";

    private static RunJournal journal(Path runDir) {
        return new FileRunJournal(runDir.resolve(JOURNAL));
    }

    private static String operator() {
        String user = System.getProperty("user.name");
        return "operator:" + (user == null ? "unknown" : user);
    }

    /** Takes the run's lock for the duration of a change; refuses (exit 2) when a live process holds it, unless forced. */
    private static Optional<RunLock> lock(Path dir, boolean force, PrintStream err) {
        Optional<RunLock> lock = RunLock.tryAcquire(dir);
        if (lock.isPresent()) return lock;
        if (force) {
            err.println("Warning: another process (pid " + RunLock.holder(dir).orElse(-1L) + ") holds this run; changing it anyway because of --force.");
            return Optional.of(RunLock.none());
        }
        err.println("Error: process " + RunLock.holder(dir).orElse(-1L) + " is working on this run. Stop it first, or pass --force.");
        return Optional.empty();
    }

    private static void audit(Path dir, String event, Map<String, Object> data) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", event);
        record.put("time", RunTravel.now());
        record.put("by", operator());
        record.putAll(data);
        try {
            Files.writeString(dir.resolve("operator-audit.jsonl"), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(record) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static RunSpec spec(Path dir, PrintStream err) {
        try {
            return RunSpec.read(dir);
        } catch (RuntimeException e) {
            err.println("Error: " + e.getMessage());
            return null;
        }
    }

    private static String effects(String given) {
        String e = given == null ? "ask-first" : given;
        return switch (e) {
            case "ask-first", "ask first" -> "ask first";
            case "keep" -> "keep";
            case "repeat" -> "repeat";
            default -> null;
        };
    }

    // ---- timeline -------------------------------------------------------------------------------------------

    @Command(name = "timeline", description = "Shows a run's history: each step with its attempt, state and cost, the checkpoints reached and every rewind.")
    static class Timeline implements Callable<Integer> {
        @Parameters(index = "0", description = "The run directory (as given to run --journal).")
        File runDir;

        @Option(names = "--json", description = "Print the timeline as JSON.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            PrintStream out = System.out;
            return show(runDir.toPath().toAbsolutePath().normalize(), json, out, System.err);
        }
    }

    static int show(Path dir, boolean json, PrintStream out, PrintStream err) throws Exception {
        if (spec(dir, err) == null) return 2;
        RunTravel.Timeline t = RunTravel.timeline(journal(dir));
        if (json) out.println(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(t));
        else out.print(t.text());
        return 0;
    }

    // ---- rewind ---------------------------------------------------------------------------------------------

    @Command(name = "rewind", description = "Sends a run back to a checkpoint (or to a step, which then runs again). Nothing is deleted: the old attempt stays as history.")
    static class Rewind implements Callable<Integer> {
        @Parameters(index = "0", description = "The run directory.")
        File runDir;

        @Option(names = "--to", required = true, description = "A checkpoint name, \"start\", or the id of a step (the first one to run again).")
        String to;

        @Option(names = "--set", description = "A value to carry into the new attempt: name=value (repeatable).")
        Map<String, String> carried = new LinkedHashMap<>();

        @Option(names = "--effects", description = "What to do about side effects already done: ask-first (the default: refuse), keep, repeat.")
        String effects;

        @Option(names = "--ask-again", description = "Ask people the questions again, even identical ones.")
        boolean askAgain;

        @Option(names = "--reason", required = true, description = "Why (recorded in the journal).")
        String reason;

        @Option(names = "--resume", description = "Carry on running the workflow from there.")
        boolean resume;

        @Option(names = "--trigger", description = "Instead of running now, leave a resume trigger for `weave tick` or `weave daemon` to pick up.")
        boolean trigger;

        @Option(names = "--force", description = "Change the run even if a process seems to be working on it.")
        boolean force;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            Path dir = runDir.toPath().toAbsolutePath().normalize();
            int code = rewind(dir, to, carried, effects, askAgain, reason, resume && !trigger, force, env);
            return code == 0 && trigger ? scheduleResume(dir, env) : code;
        }
    }

    static int rewind(Path dir, String to, Map<String, String> carried, String effectsOption, boolean askAgain, String reason, boolean resume, boolean force, WeaveEnv env) {
        RunSpec spec = spec(dir, env.err());
        if (spec == null) return 2;
        String effects = effects(effectsOption);
        if (effects == null) {
            env.err().println("Error: --effects takes ask-first, keep or repeat.");
            return 2;
        }
        Optional<RunLock> lock = lock(dir, force, env.err());
        if (lock.isEmpty()) return 2;
        try {
            Generations.Boundary b = RunTravel.rewind(journal(dir), spec.workflow(), to, carried, effects, askAgain, reason, operator());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("to", to);
            data.put("generation", b.generation());
            data.put("reason", reason);
            data.put("effects", effects);
            if (force) data.put("forced", true);
            audit(dir, "run_rewound", data);
            env.out().println("⏪ Rewound " + dir.getFileName() + " to " + to + ": the next attempt is generation " + b.generation() + ". The earlier attempt stays in the journal.");
        } catch (RunTravel.Held held) {
            env.err().println("Error: " + held.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } finally {
            lock.ifPresent(RunLock::close);
        }
        return resume ? WeaveCLI.resume(dir, env) : 0;
    }

    /** Leaves a resume trigger, due now, in the run's trigger store, as a pause for a limit would, so a scheduler carries the run on. */
    static int scheduleResume(Path dir, WeaveEnv env) {
        RunSpec spec = spec(dir, env.err());
        if (spec == null || spec.store() == null) {
            env.err().println("Error: this run has no trigger store to leave a trigger in.");
            return 2;
        }
        Path store = Path.of(spec.store());
        if (dir.startsWith(store.resolve("runs"))) {
            env.err().println("Error: this run belongs to a schedule; resume it with: weave resume " + Runs.quote(dir.toString()));
            return 2;
        }
        new io.github.llm4j.loom.trigger.FileTriggerStore(store).upsert(
                io.github.llm4j.loom.trigger.Trigger.resume(dir.toString(), env.clock().instant(), "an operator sent the run back", 0));
        env.out().println("⏰ Left a resume trigger in " + store + ": `weave tick` or `weave daemon` will carry the run on.");
        return 0;
    }

    // ---- reset ----------------------------------------------------------------------------------------------

    @Command(name = "reset", description = "Starts a run again from the top as a new attempt (spend history kept), or with --failed tries only the failed steps again.")
    static class Reset implements Callable<Integer> {
        @Parameters(index = "0", description = "The run directory.")
        File runDir;

        @Option(names = "--failed", description = "Only the steps that failed are tried again; everything else stays as it was.")
        boolean failed;

        @Option(names = "--effects", description = "What to do about side effects already done: ask-first (the default: refuse), keep, repeat.")
        String effects;

        @Option(names = "--reason", required = true, description = "Why (recorded).")
        String reason;

        @Option(names = "--resume", description = "Carry on running the workflow afterwards.")
        boolean resume;

        @Option(names = "--trigger", description = "Instead of running now, leave a resume trigger for `weave tick` or `weave daemon` to pick up.")
        boolean trigger;

        @Option(names = "--force", description = "Change the run even if a process seems to be working on it.")
        boolean force;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            Path dir = runDir.toPath().toAbsolutePath().normalize();
            int code = reset(dir, failed, effects, reason, resume && !trigger, force, env);
            return code == 0 && trigger ? scheduleResume(dir, env) : code;
        }
    }

    static int reset(Path dir, boolean failedOnly, String effectsOption, String reason, boolean resume, boolean force, WeaveEnv env) {
        RunSpec spec = spec(dir, env.err());
        if (spec == null) return 2;
        String policy = effects(effectsOption);
        if (policy == null) {
            env.err().println("Error: --effects takes ask-first, keep or repeat.");
            return 2;
        }
        Optional<RunLock> lock = lock(dir, force, env.err());
        if (lock.isEmpty()) return 2;
        try {
            List<String> changed = RunTravel.reset(journal(dir), spec.workflow(), failedOnly, policy, reason, operator());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("failedOnly", failedOnly);
            data.put("reason", reason);
            data.put("changed", changed);
            if (force) data.put("forced", true);
            audit(dir, "run_reset", data);
            env.out().println("♻ Reset " + dir.getFileName() + ": " + (changed.isEmpty() ? "nothing to change" : String.join("; ", changed)));
        } catch (RunTravel.Held held) {
            env.err().println("Error: " + held.getMessage());
            return 2;
        } finally {
            lock.ifPresent(RunLock::close);
        }
        return resume ? WeaveCLI.resume(dir, env) : 0;
    }

    // ---- fork -----------------------------------------------------------------------------------------------

    @Command(name = "fork", description = "Copies a run into a new run directory (the original is only read), optionally going back to a point, with other values or another script.")
    static class Fork implements Callable<Integer> {
        @Parameters(index = "0", description = "The run directory to copy.")
        File runDir;

        @Option(names = "--to", required = true, description = "The new run directory.")
        File newDir;

        @Option(names = "--at", description = "A checkpoint or step id to go back to in the copy.")
        String at;

        @Option(names = "--script", description = "Run the copy under another script.")
        File script;

        @Option(names = "--set", description = "A value to carry into the new attempt: name=value (repeatable; needs --at).")
        Map<String, String> carried = new LinkedHashMap<>();

        @Option(names = "--effects", description = "keep (the default: identical effects are found, not repeated), simulate (nothing is performed in the copy, ever), or repeat.")
        String effects;

        @Option(names = "--until", description = "Stop the copy cleanly once this step has completed.")
        String until;

        @Option(names = "--allow-drift", description = "Accept a script whose earlier statements differ from the run's.")
        boolean allowDrift;

        @Option(names = "--reason", required = true, description = "Why (recorded in the copy).")
        String reason;

        @Option(names = "--resume", description = "Run the copy now.")
        boolean resume;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return fork(runDir.toPath().toAbsolutePath().normalize(), newDir.toPath().toAbsolutePath().normalize(), at, script, carried, effects, until, allowDrift, reason, resume, env);
        }
    }

    static int fork(Path parent, Path child, String at, File script, Map<String, String> carried, String effectsOption, String until, boolean allowDrift,
                    String reason, boolean resume, WeaveEnv env) {
        RunSpec spec = spec(parent, env.err());
        if (spec == null) return 2;
        String mode = effectsOption == null ? "keep" : effectsOption;
        if (!List.of("keep", "simulate", "repeat").contains(mode)) {
            env.err().println("Error: --effects takes keep, simulate or repeat.");
            return 2;
        }
        if (Files.exists(child.resolve(JOURNAL)) || Files.exists(child.resolve(RunSpec.FILE))) {
            env.err().println("Error: " + child + " already holds a run. Pick a new directory.");
            return 2;
        }
        RunJournal from = journal(parent);
        if (script != null && !allowDrift) {
            String drift = ScriptDrift.between(Path.of(spec.script()), script.toPath(), from, spec.workflow(), at);
            if (drift != null) {
                env.err().println("Error: " + drift + " Pass --allow-drift to run the copy under this script anyway.");
                return 2;
            }
        }
        RunJournal to = new FileRunJournal(child.resolve(JOURNAL));
        int entries = RunTravel.copy(from, to);
        Generations.Boundary b = null;
        try {
            if (at != null) b = RunTravel.rewind(to, spec.workflow(), at, carried, mode.equals("repeat") ? "repeat" : "keep", false, reason, operator());
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        Map<String, Object> forkOf = new LinkedHashMap<>();
        forkOf.put("run", parent.toString());
        forkOf.put("at", at);
        forkOf.put("entries", entries);
        forkOf.put("reason", reason);
        forkOf.put("by", operator());
        forkOf.put("time", RunTravel.now());
        if (script != null) {
            forkOf.put("script", script.getAbsolutePath());
            if (allowDrift) forkOf.put("drift", "accepted");
        }
        RunSpec forked = new RunSpec(script != null ? script.getAbsolutePath() : spec.script(), spec.loot(), spec.workflow(), spec.inputs(), spec.maxTokens(), spec.maxCalls(),
                spec.maxCost(), spec.prices(), Runs.defaultStore(child).toString(), spec.lenient(), spec.trace(), mode.equals("simulate") || spec.simulate(), forkOf, null);
        forked.write(child);
        audit(child, "run_forked", forkOf);
        env.out().println("🍴 Forked " + parent.getFileName() + " into " + child + " (" + entries + " journal entries copied" + (b != null ? ", going back to " + at + ": generation " + b.generation() : "")
                + (mode.equals("simulate") ? "; simulated: nothing will be performed" : "") + "). The original is untouched.");
        if (!resume && until == null) return 0;
        return WeaveCLI.resume(child, until, env);
    }
}
