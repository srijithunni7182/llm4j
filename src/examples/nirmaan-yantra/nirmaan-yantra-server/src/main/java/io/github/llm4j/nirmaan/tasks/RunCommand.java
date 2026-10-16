package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Dhruv's job: run a build, test or end-to-end command in the project and report what happened, with no model involved.
 * {@code run RunCommand(project = id, command = spec_doc.test_command, label = "green") -> test_run}.
 *
 * <p>{@code outcome} is {@code passed} (every step exited 0), {@code failed} (one did not), {@code timed_out}, or {@code refused}
 * (the command is on the blocked list, could not be understood or started, or this deployment requires a container). They are four
 * different words on purpose: the workflow's {@code x is ok} is true for exactly one of them, {@code passed}, so a hang is never mistaken for a
 * test that failed as it should. {@code value} is the end of the output, which is what an agent needs to fix a failure; the whole log
 * is kept in the project's {@code meta/logs} folder.
 *
 * <p>A command the policy cannot vouch for ({@link CommandPolicy.Decision#NEEDS_REVIEW}) is not run until a person approves it:
 * {@link #requiresApproval} makes the runtime ask first, whatever the script says. Commands start directly, never through a shell,
 * in a clean environment (no model keys, a private tool home instead of the real one), or in a container when
 * {@code NIRMAAN_SANDBOX} says so ({@link SandboxConfig}).
 */
public final class RunCommand implements Task {

    static final int TAIL_CHARS = 12_000;

    private final long timeoutSeconds;
    private final SandboxConfig sandbox;

    public RunCommand() {
        this(parseTimeout(System.getenv("NIRMAAN_COMMAND_TIMEOUT_SECONDS")), SandboxConfig.fromEnvironment());
    }

    RunCommand(long timeoutSeconds) {
        this(timeoutSeconds, SandboxConfig.local());
    }

    RunCommand(long timeoutSeconds, SandboxConfig sandbox) {
        this.timeoutSeconds = timeoutSeconds;
        this.sandbox = sandbox;
    }

    private static long parseTimeout(String text) {
        try {
            return text == null ? 300 : Math.max(1, Long.parseLong(text.strip()));
        } catch (NumberFormatException e) {
            return 300;
        }
    }

    @Override public String getName() { return "RunCommand"; }

    @Override public String getDescription() { return "Runs a build, test or run command in the project and reports the result"; }

    @Override public TaskEffect effect() { return TaskEffect.CHANGES; }

    /** {@code x is ok} is true for a command that passed, and for nothing else: not a failure, not a hang, not a refusal. */
    @Override public java.util.Set<String> okOutcomes() { return java.util.Set.of("passed"); }

    @Override public EffectPolicy policy() { return Tasks.REPEATABLE; }

    @Override
    public boolean requiresApproval(Map<String, Object> args) {
        Object command = args.get("command");
        if (command == null || String.valueOf(command).strip().equalsIgnoreCase("none")) return false;
        return CommandPolicy.classify(String.valueOf(command)).decision() == CommandPolicy.Decision.NEEDS_REVIEW;
    }

    @Override
    public TaskResult run(TaskContext context) throws IOException, InterruptedException {
        String project = context.requireArg("project", String.class);
        String command = context.requireArg("command", String.class).strip();
        String label = context.arg("label", String.class) == null ? "command" : context.arg("label", String.class).replaceAll("[^A-Za-z0-9_-]", "_");
        if (command.equalsIgnoreCase("none")) return TaskResult.outcome("passed").reason("there is no command to run").withValue("");

        CommandPolicy.Result verdict = CommandPolicy.classify(command);
        if (verdict.decision() == CommandPolicy.Decision.BLOCKED) return refused(verdict.reason());
        // NEEDS_REVIEW reaches here only after a person said yes (requiresApproval); it runs the same way.
        List<List<String>> parts = verdict.decision() == CommandPolicy.Decision.APPROVED ? verdict.parts() : CommandPolicy.parts(command);
        if (parts.isEmpty()) return refused("the command could not be understood: " + command);

        if (sandbox.requireContainer() && !sandbox.container()) {
            return refused("this deployment requires a container sandbox (NIRMAAN_REQUIRE_CONTAINER) and NIRMAAN_SANDBOX is local");
        }
        Path root = Sandbox.code(project);
        StringBuilder log = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        int exit = 0;
        boolean timedOut = false;
        for (List<String> words : parts) {
            log.append("$ ").append(String.join(" ", words)).append('\n');
            SandboxConfig.Launch launch = sandbox.launch(words, root, Sandbox.workspace(), project);
            Process process;
            try {
                process = launch.builder().start();
            } catch (IOException e) {
                log.append("could not start \"").append(words.get(0)).append("\": ").append(e.getMessage()).append('\n');
                exit = 127;
                break;
            }
            // Read on a helper thread so a silent hang is still cut off by the deadline.
            Thread reader = new Thread(() -> drain(process.getInputStream(), log), "nirmaan-command-output");
            reader.setDaemon(true);
            reader.start();
            long left = Math.max(1, deadline - System.nanoTime());
            if (!process.waitFor(left, TimeUnit.NANOSECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                launch.stop().run();
                timedOut = true;
                reader.join(2000);
                break;
            }
            reader.join(2000);
            exit = process.exitValue();
            if (exit != 0) break;
        }

        String full = log.toString();
        Path logs = Files.createDirectories(Sandbox.meta(project).resolve("logs"));
        Files.writeString(logs.resolve(label + ".log"), full, StandardCharsets.UTF_8);
        String tail = full.length() <= TAIL_CHARS ? full : "[...earlier output cut...]\n" + full.substring(full.length() - TAIL_CHARS);
        if (timedOut) return TaskResult.outcome("timed_out").reason("no result after " + timeoutSeconds + " seconds").withValue(tail);
        if (exit != 0) return TaskResult.outcome("failed").reason("exit code " + exit).withValue(tail).with("exit_code", exit);
        return TaskResult.outcome("passed").withValue(tail).with("exit_code", 0);
    }

    private static TaskResult refused(String why) {
        return TaskResult.outcome("refused").reason(why).withValue("");
    }

    private static void drain(InputStream in, StringBuilder log) {
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = in.read(buffer)) > 0) {
                synchronized (log) {
                    if (log.length() < 4_000_000) log.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            // the process ended or was killed
        }
    }
}
