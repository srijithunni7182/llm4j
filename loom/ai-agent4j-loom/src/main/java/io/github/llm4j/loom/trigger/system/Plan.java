package io.github.llm4j.loom.trigger.system;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What installing (or removing) a system trigger would change — files to write or delete and commands to
 * run — as plain data, so it can be shown to the user before anything happens and tested without
 * touching the machine.
 */
public record Plan(String backend, List<FileWrite> writes, List<Path> deletes, List<Command> commands, List<String> notes) {

    public record FileWrite(Path path, String content) { }

    /** A command; {@code stdin} is fed to it (e.g. a new crontab). A command that {@code mayFail} is best effort. */
    public record Command(List<String> argv, String stdin, boolean mayFail) {
        public static Command of(String... argv) {
            return new Command(List.of(argv), null, false);
        }

        public static Command bestEffort(String... argv) {
            return new Command(List.of(argv), null, true);
        }

        public String display() {
            return String.join(" ", argv.stream().map(Quote::shell).toList()) + (stdin != null ? " < (new content)" : "");
        }
    }

    public Plan {
        writes = List.copyOf(writes);
        deletes = List.copyOf(deletes);
        commands = List.copyOf(commands);
        notes = List.copyOf(notes);
    }

    public boolean isEmpty() {
        return writes.isEmpty() && deletes.isEmpty() && commands.isEmpty();
    }

    /** A readable summary, as {@code weave triggers install} prints it before (or instead of) applying. */
    public String describe() {
        List<String> lines = new ArrayList<>();
        lines.add("System trigger (" + backend + "):");
        for (FileWrite w : writes) {
            lines.add("  write  " + w.path());
            for (String l : w.content().split("\n")) lines.add("         | " + l);
        }
        for (Path d : deletes) lines.add("  delete " + d);
        for (Command c : commands) {
            lines.add("  run    " + c.display());
            if (c.stdin() != null) {
                for (String l : c.stdin().split("\n")) lines.add("         | " + l);
            }
        }
        for (String n : notes) lines.add("  note   " + n);
        if (isEmpty()) lines.add("  (nothing to change)");
        return String.join("\n", lines) + "\n";
    }
}
