package io.github.llm4j.loom.trigger.system;

import io.github.llm4j.loom.trigger.Trigger;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A macOS LaunchAgent: {@code ~/Library/LaunchAgents/dev.llm4j.loom.<id>.plist} with
 * {@code StartInterval}; exact mode adds {@code StartCalendarInterval} entries (local time).
 */
public final class LaunchdBackend implements SystemTriggerBackend {

    @Override
    public String name() {
        return "launchd";
    }

    static String label(InstallRequest r) {
        return "dev.llm4j.loom." + r.storeId();
    }

    static Path plist(InstallRequest r) {
        return r.home().resolve("Library/LaunchAgents/" + label(r) + ".plist");
    }

    @Override
    public Plan install(InstallRequest r, CommandRunner runner) throws IOException {
        List<String> argv = r.tickCommand();
        if (r.envFile() != null) {
            argv = List.of("/bin/sh", "-c", "set -a; . " + Quote.shell(r.envFile().toString()) + "; exec " + Quote.shell(r.tickCommand()));
        }
        StringBuilder args = new StringBuilder();
        for (String a : argv) args.append("    <string>").append(Quote.xml(a)).append("</string>\n");
        StringBuilder calendar = new StringBuilder();
        if (r.mode() == InstallRequest.Mode.EXACT) {
            calendar.append("  <key>StartCalendarInterval</key>\n  <array>\n");
            for (Trigger t : r.pending()) {
                if (!t.enabled() || t.nextFire() == null) continue;
                ZonedDateTime at = t.nextFire().atZone(r.zone());
                if (at.getSecond() > 0 || at.getNano() > 0) at = at.plusMinutes(1).withSecond(0).withNano(0);
                calendar.append("    <dict><key>Month</key><integer>").append(at.getMonthValue())
                        .append("</integer><key>Day</key><integer>").append(at.getDayOfMonth())
                        .append("</integer><key>Hour</key><integer>").append(at.getHour())
                        .append("</integer><key>Minute</key><integer>").append(at.getMinute()).append("</integer></dict>\n");
            }
            calendar.append("  </array>\n");
        }
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>Label</key>
                  <string>%s</string>
                  <key>ProgramArguments</key>
                  <array>
                %s  </array>
                  <key>StartInterval</key>
                  <integer>%d</integer>
                %s  <key>RunAtLoad</key>
                  <true/>
                </dict>
                </plist>
                """.formatted(label(r), args, r.heartbeatMinutes() * 60, calendar);
        String domain = "gui/" + (r.uid() != null ? r.uid() : "501");
        return new Plan(name(), List.of(new Plan.FileWrite(plist(r), content)), List.of(), List.of(
                Plan.Command.bestEffort("launchctl", "bootout", domain + "/" + label(r)),
                Plan.Command.of("launchctl", "bootstrap", domain, plist(r).toString())), List.of());
    }

    @Override
    public Plan uninstall(InstallRequest r, CommandRunner runner) {
        String domain = "gui/" + (r.uid() != null ? r.uid() : "501");
        return new Plan(name(), List.of(), List.of(plist(r)),
                List.of(Plan.Command.bestEffort("launchctl", "bootout", domain + "/" + label(r))), List.of());
    }
}
