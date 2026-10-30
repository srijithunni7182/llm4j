package io.github.llm4j.loom.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Runs the real {@code weave} command line from a test in another package, capturing its output. */
public final class FieldCli {
    /** The exit code and everything written to standard output and error. */
    public record Result(int exit, String out, String err) {}

    private FieldCli() {}

    public static synchronized Result weave(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = WeaveCLI.commandLine().execute(args);
            return new Result(
                    exit,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
