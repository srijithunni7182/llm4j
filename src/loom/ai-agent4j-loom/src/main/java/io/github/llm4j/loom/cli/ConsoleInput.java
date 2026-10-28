package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.execution.InterventionCommands;
import io.github.llm4j.loom.runtime.NoHumanAvailable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The one reader of the console while a run can be steered from it ({@code --steer-via console}). A
 * line typed while a person is being asked a question is the answer; any other line is a command to
 * the runs in this process (steer, wrap up, cancel, pause, resume, agents), answered on the
 * console. Without the router the console works as it always did.
 */
final class ConsoleInput {

    private static final long CLOSED_CHECK_MILLIS = 100;

    private static ConsoleInput active;

    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private final PrintStream out;
    private final InterventionCommands commands;
    private volatile boolean asking;
    private volatile boolean closed;

    private ConsoleInput(PrintStream out, InterventionCommands commands) {
        this.out = out;
        this.commands = commands;
    }

    /** Starts reading {@code in}; once started it stays for the life of the process. */
    static synchronized void start(InputStream in, PrintStream out) {
        if (active != null) return;
        active = new ConsoleInput(out, InterventionCommands.forThisProcess());
        ConsoleInput router = active;
        Thread t = new Thread(() -> router.read(in), "loom-console-commands");
        t.setDaemon(true);
        t.start();
        out.println(
                "🧭 Steering from this console: steer [Agent] <text> · wrap up [Agent] · cancel [Agent] [outcome] [: reason] · cancel [Agent] back to <checkpoint> [: reason] · pause · resume · agents");
    }

    /** Stops routing (for tests that start it more than once in a process). */
    static synchronized void reset() {
        active = null;
    }

    static synchronized boolean routing() {
        return active != null;
    }

    /**
     * The next line a person types as an answer; commands typed meanwhile are still carried out.
     */
    static String answer() {
        return answer("");
    }

    /**
     * As {@link #answer()}, naming the question so that a console that closes can say what it was.
     */
    static String answer(String question) {
        ConsoleInput router;
        synchronized (ConsoleInput.class) {
            router = active;
        }
        router.asking = true;
        try {
            // the console may close while a question is open: stop waiting for a line that cannot
            // come
            while (true) {
                String line = router.answers.poll(CLOSED_CHECK_MILLIS, TimeUnit.MILLISECONDS);
                if (line != null) return line;
                if (router.closed && router.answers.isEmpty()) throw new NoHumanAvailable(question);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an answer", e);
        } finally {
            router.asking = false;
        }
    }

    private void read(InputStream in) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() && !asking) continue;
                if (asking) {
                    answers.offer(line);
                } else if (commands.isCommand(line)) {
                    out.println(commands.handle(line, "console"));
                } else {
                    out.println(
                            "Not a command here. Try: agents, steer, wrap up, cancel, pause, resume.");
                }
            }
        } catch (IOException e) {
            // the console closed: nothing more can be typed
        } finally {
            closed = true;
        }
    }
}
