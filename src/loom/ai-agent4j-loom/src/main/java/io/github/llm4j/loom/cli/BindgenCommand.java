package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.bind.Bindgen;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave bindgen}: typed Java bindings for a script (a facade, a record per result, an
 * outcome that makes waiting explicit). {@code --check} regenerates in memory and fails when the
 * files on disk differ, so a script changed without regenerating is caught in CI.
 */
@Command(
        name = "bindgen",
        description =
                "Writes typed Java bindings for a script: one method per workflow, a record per result, and an Outcome that is Done, Waiting or Stopped. --check fails when the files on disk are out of date.")
final class BindgenCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "script.loom", description = "The script.")
    File script;

    @Option(
            names = "--package",
            required = true,
            description = "The Java package of the generated files, like com.acme.triage.")
    String pkg;

    @Option(names = "--out", description = "The source root to write into (default src/main/java).")
    File out = new File("src/main/java");

    @Option(
            names = "--facade",
            description = "The facade's name (default: from the script's name).")
    String facade;

    @Option(
            names = "--nullability",
            defaultValue = "none",
            description =
                    "How a field not set on every path is shown: none (plain, documented), jspecify (@Nullable; needs org.jspecify) or optional (Optional<T>).")
    String nullability;

    @Option(
            names = "--check",
            description =
                    "Write nothing; exit 1 when the files on disk differ from what would be written.")
    boolean check;

    @Override
    public Integer call() {
        return run(this, WeaveEnv.system());
    }

    static int run(BindgenCommand c, WeaveEnv env) {
        try {
            if (!c.script.isFile()) {
                env.err().println("Error: the script " + c.script + " is not there.");
                return 2;
            }
            // the script must pass `weave check`: no partial output from a script that does not
            int code =
                    WeaveCLI.check(
                            c.script,
                            null,
                            new WeaveCLI.CheckSettings(false, false, true, false),
                            env);
            if (code != 0) {
                env.err().println("Error: the script does not check, so no bindings were written.");
                return 1;
            }
            Bindgen.Nullability n;
            try {
                n = Bindgen.Nullability.valueOf(c.nullability.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                env.err().println("Error: --nullability is none, jspecify or optional.");
                return 2;
            }
            Bindgen.Generated g = Bindgen.generate(c.script.toPath(), c.pkg, c.facade, n);
            Path root = c.out.toPath();
            List<String> stale = new ArrayList<>();
            for (Map.Entry<String, String> f : g.files().entrySet()) {
                Path target = root.resolve(f.getKey());
                String disk =
                        Files.isRegularFile(target)
                                ? Files.readString(target, StandardCharsets.UTF_8)
                                : null;
                if (!f.getValue().equals(disk)) stale.add(f.getKey());
                if (!c.check && !f.getValue().equals(disk)) {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
                }
            }
            g.notes().forEach(note -> env.out().println("Note: " + note));
            if (c.check) {
                if (stale.isEmpty()) {
                    env.out().println("The bindings are up to date.");
                    return 0;
                }
                env.err()
                        .println(
                                "Error: out of date: "
                                        + String.join(", ", stale)
                                        + ". Run: weave bindgen "
                                        + c.script
                                        + " --package "
                                        + c.pkg);
                return 1;
            }
            env.out()
                    .println(
                            stale.isEmpty()
                                    ? "The bindings were already up to date."
                                    : "Wrote " + String.join(", ", stale) + " under " + root);
            return 0;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        }
    }
}
