package io.github.llm4j.loom.trigger.system;

import io.github.llm4j.loom.trigger.Trigger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A systemd user timer: {@code ~/.config/systemd/user/loom-<id>.service} and {@code .timer}
 * ({@code Persistent=true}, so a tick missed while the machine was off runs at boot). Exact mode adds a
 * {@code loom-<id>-<trigger>.timer} per pending trigger.
 */
public final class SystemdBackend implements SystemTriggerBackend {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    @Override
    public String name() {
        return "systemd";
    }

    static Path unitDir(InstallRequest r) {
        return r.home().resolve(".config/systemd/user");
    }

    static String unit(InstallRequest r) {
        return "loom-" + r.storeId();
    }

    @Override
    public Plan install(InstallRequest r, CommandRunner runner) throws IOException {
        Path dir = unitDir(r);
        String unit = unit(r);
        List<Plan.FileWrite> writes = new ArrayList<>();
        List<Plan.Command> commands = new ArrayList<>();
        String exec = String.join(" ", r.tickCommand().stream().map(Quote::systemd).toList());
        writes.add(new Plan.FileWrite(dir.resolve(unit + ".service"), """
                [Unit]
                Description=Loom triggers for %s

                [Service]
                Type=oneshot
                %sExecStart=%s
                """.formatted(r.store(), r.envFile() != null ? "EnvironmentFile=" + r.envFile() + "\n" : "", exec)));
        long m = r.heartbeatMinutes();
        String calendar = m < 60 ? "*:0/" + m : "*-*-* 0/" + Math.max(1, m / 60) + ":00:00";
        writes.add(new Plan.FileWrite(dir.resolve(unit + ".timer"), timer("Wake Loom every " + m + "m for " + r.store(),
                "OnCalendar=" + calendar + "\nPersistent=true", unit)));
        List<String> timers = new ArrayList<>(List.of(unit + ".timer"));
        List<Path> deletes = new ArrayList<>();
        if (r.mode() == InstallRequest.Mode.EXACT) {
            List<String> wanted = new ArrayList<>();
            for (Trigger t : r.pending()) {
                if (!t.enabled() || t.nextFire() == null) continue;
                String name = unit + "-" + InstallRequest.shortHash(t.id()) + ".timer";
                wanted.add(name);
                writes.add(new Plan.FileWrite(dir.resolve(name), timer("Loom trigger " + t.id(),
                        "OnCalendar=" + AT.format(t.nextFire()) + " UTC\nPersistent=true", unit)));
                timers.add(name);
            }
            for (Path stale : existingExact(dir, unit)) {
                if (!wanted.contains(stale.getFileName().toString())) {
                    commands.add(Plan.Command.bestEffort("systemctl", "--user", "disable", "--now", stale.getFileName().toString()));
                    deletes.add(stale);
                }
            }
        }
        commands.add(Plan.Command.of("systemctl", "--user", "daemon-reload"));
        for (String t : timers) commands.add(Plan.Command.of("systemctl", "--user", "enable", "--now", t));
        return new Plan(name(), writes, deletes, commands, List.of());
    }

    private static String timer(String description, String when, String unit) {
        return """
                [Unit]
                Description=%s

                [Timer]
                %s
                Unit=%s.service

                [Install]
                WantedBy=timers.target
                """.formatted(description, when, unit);
    }

    private static List<Path> existingExact(Path dir, String unit) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(unit + "-")
                    && p.getFileName().toString().endsWith(".timer")).sorted().toList();
        }
    }

    @Override
    public Plan uninstall(InstallRequest r, CommandRunner runner) throws IOException {
        Path dir = unitDir(r);
        String unit = unit(r);
        List<Plan.Command> commands = new ArrayList<>();
        List<Path> deletes = new ArrayList<>();
        for (Path exact : existingExact(dir, unit)) {
            commands.add(Plan.Command.bestEffort("systemctl", "--user", "disable", "--now", exact.getFileName().toString()));
            deletes.add(exact);
        }
        commands.add(Plan.Command.bestEffort("systemctl", "--user", "disable", "--now", unit + ".timer"));
        deletes.add(dir.resolve(unit + ".timer"));
        deletes.add(dir.resolve(unit + ".service"));
        commands.add(Plan.Command.of("systemctl", "--user", "daemon-reload"));
        return new Plan(name(), List.of(), deletes, commands, List.of());
    }
}
