package io.github.llm4j.loom.trigger.system;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/** Finds backends, picks one for this machine, and applies plans. */
public final class SystemTriggers {

    public static final List<String> NAMES = List.of("cron", "systemd", "launchd", "windows", "cloud-scheduler");

    private SystemTriggers() { }

    public static SystemTriggerBackend backend(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "cron" -> new CronBackend();
            case "systemd" -> new SystemdBackend();
            case "launchd" -> new LaunchdBackend();
            case "windows" -> new WindowsBackend();
            case "cloud-scheduler" -> new CloudSchedulerBackend();
            default -> throw new IllegalArgumentException("Unknown backend '" + name + "'. Use one of " + NAMES);
        };
    }

    /** systemd (if {@code systemctl --user} works) or cron on Linux, launchd on macOS, windows on Windows. */
    public static String detect(String osName, CommandRunner runner) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "launchd";
        try {
            if (runner.run(Plan.Command.of("systemctl", "--user", "is-system-running")).exit() >= 0
                    && runner.run(Plan.Command.of("systemctl", "--user", "list-timers")).exit() == 0) {
                return "systemd";
            }
        } catch (IOException | RuntimeException noSystemd) {
            // fall through
        }
        return "cron";
    }

    /** Writes, deletes and runs what the plan says; a failing command (unless best effort) stops it. */
    public static void apply(Plan plan, CommandRunner runner) throws IOException {
        for (Plan.FileWrite w : plan.writes()) {
            Files.createDirectories(w.path().getParent());
            Files.writeString(w.path(), w.content());
        }
        for (java.nio.file.Path d : plan.deletes()) Files.deleteIfExists(d);
        for (Plan.Command c : plan.commands()) {
            CommandRunner.Result r = runner.run(c);
            if (r.exit() != 0 && !c.mayFail()) {
                throw new IOException("'" + c.display() + "' failed (exit " + r.exit() + "): " + r.stderr().strip());
            }
        }
    }
}
