package io.github.llm4j.loom.trigger.system;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Runs a command. The real one starts a process; tests supply a fake. */
@FunctionalInterface
public interface CommandRunner {

    record Result(int exit, String stdout, String stderr) { }

    Result run(Plan.Command command) throws IOException;

    CommandRunner SYSTEM = command -> {
        Process p = new ProcessBuilder(command.argv()).start();
        try (OutputStream in = p.getOutputStream()) {
            if (command.stdin() != null) in.write(command.stdin().getBytes(StandardCharsets.UTF_8));
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return new Result(p.waitFor(), out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted running " + command.argv(), e);
        }
    };
}
