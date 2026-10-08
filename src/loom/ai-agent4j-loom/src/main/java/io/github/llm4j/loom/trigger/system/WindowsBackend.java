package io.github.llm4j.loom.trigger.system;

import io.github.llm4j.loom.trigger.Trigger;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A per-user Windows scheduled task, {@code \Loom\<id>}, repeating every few minutes. Exact mode adds a
 * one-time task per pending trigger, defined by task XML (locale-independent times).
 */
public final class WindowsBackend implements SystemTriggerBackend {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Override
    public String name() {
        return "windows";
    }

    static String task(InstallRequest r) {
        return "\\Loom\\" + r.storeId();
    }

    private static String commandLine(InstallRequest r) {
        return String.join(" ", r.tickCommand().stream().map(Quote::windows).toList());
    }

    @Override
    public Plan install(InstallRequest r, CommandRunner runner) throws IOException {
        List<Plan.Command> commands = new ArrayList<>();
        List<Plan.FileWrite> writes = new ArrayList<>();
        commands.add(Plan.Command.of("schtasks", "/Create", "/TN", task(r), "/SC", "MINUTE", "/MO",
                String.valueOf(r.heartbeatMinutes()), "/TR", commandLine(r), "/F"));
        List<String> notes = new ArrayList<>();
        if (r.envFile() != null) notes.add("Windows tasks can't load an env file: set API keys as user environment variables");
        if (r.mode() == InstallRequest.Mode.EXACT) {
            for (Trigger t : r.pending()) {
                if (!t.enabled() || t.nextFire() == null) continue;
                String name = task(r) + "-" + InstallRequest.shortHash(t.id());
                Path xml = r.store().resolve("system/" + r.storeId() + "-" + InstallRequest.shortHash(t.id()) + ".xml");
                List<String> argv = r.tickCommand();
                writes.add(new Plan.FileWrite(xml, """
                        <?xml version="1.0" encoding="UTF-16"?>
                        <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                          <Triggers><TimeTrigger><StartBoundary>%s</StartBoundary><Enabled>true</Enabled></TimeTrigger></Triggers>
                          <Settings><StartWhenAvailable>true</StartWhenAvailable></Settings>
                          <Actions><Exec><Command>%s</Command><Arguments>%s</Arguments></Exec></Actions>
                        </Task>
                        """.formatted(AT.format(t.nextFire()), Quote.xml(argv.get(0)),
                        Quote.xml(String.join(" ", argv.subList(1, argv.size()).stream().map(Quote::windows).toList())))));
                commands.add(Plan.Command.of("schtasks", "/Create", "/TN", name, "/XML", xml.toString(), "/F"));
            }
        }
        return new Plan(name(), writes, List.of(), commands, notes);
    }

    @Override
    public Plan uninstall(InstallRequest r, CommandRunner runner) throws IOException {
        List<Plan.Command> commands = new ArrayList<>();
        CommandRunner.Result list = runner.run(Plan.Command.of("schtasks", "/Query", "/FO", "CSV", "/NH"));
        if (list.exit() == 0) {
            for (String line : list.stdout().split("\n")) {
                String name = line.split(",", 2)[0].replace("\"", "").trim();
                if (name.startsWith(task(r) + "-")) commands.add(Plan.Command.bestEffort("schtasks", "/Delete", "/TN", name, "/F"));
            }
        }
        commands.add(Plan.Command.bestEffort("schtasks", "/Delete", "/TN", task(r), "/F"));
        return new Plan(name(), List.of(), List.of(), commands, List.of());
    }
}
