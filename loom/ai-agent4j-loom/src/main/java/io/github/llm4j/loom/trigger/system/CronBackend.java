package io.github.llm4j.loom.trigger.system;

import io.github.llm4j.loom.trigger.Trigger;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The user's crontab: one tagged heartbeat line ({@code *}{@code /5 * * * * <weave> tick <store> # loom:<id>});
 * in exact mode also one line per pending trigger, at its minute. Other lines are never touched.
 */
public final class CronBackend implements SystemTriggerBackend {

    @Override
    public String name() {
        return "cron";
    }

    private static String tag(InstallRequest r) {
        return "# loom:" + r.storeId();
    }

    static String current(CommandRunner runner) throws IOException {
        CommandRunner.Result r = runner.run(Plan.Command.of("crontab", "-l"));
        if (r.exit() != 0) return ""; // "no crontab for user"
        return r.stdout();
    }

    private static List<String> othersLines(String crontab, String tag) {
        List<String> keep = new ArrayList<>();
        for (String line : crontab.split("\n", -1)) {
            if (line.endsWith(tag) || line.contains(tag + " ")) continue;
            keep.add(line);
        }
        while (!keep.isEmpty() && keep.get(keep.size() - 1).isEmpty()) keep.remove(keep.size() - 1);
        return keep;
    }

    String command(InstallRequest r) {
        String tick = Quote.shell(r.tickCommand());
        if (r.envFile() == null) return tick;
        return "/bin/sh -c " + Quote.shell("set -a; . " + Quote.shell(r.envFile().toString()) + "; exec " + tick);
    }

    @Override
    public Plan install(InstallRequest r, CommandRunner runner) throws IOException {
        String tag = tag(r);
        List<String> lines = othersLines(current(runner), tag);
        long m = r.heartbeatMinutes();
        String every = m < 60 ? "*/" + m + " * * * *" : "0 */" + Math.max(1, m / 60) + " * * *";
        lines.add(every + " " + command(r) + " " + tag);
        List<String> notes = new ArrayList<>();
        if (r.mode() == InstallRequest.Mode.EXACT) {
            for (Trigger t : r.pending()) {
                if (!t.enabled() || t.nextFire() == null) continue;
                ZonedDateTime at = t.nextFire().atZone(r.zone());
                // cron has minute resolution: round up so it never fires before the trigger is due
                if (at.getSecond() > 0 || at.getNano() > 0) at = at.plusMinutes(1).withSecond(0).withNano(0);
                lines.add(at.getMinute() + " " + at.getHour() + " " + at.getDayOfMonth() + " " + at.getMonthValue()
                        + " * " + command(r) + " " + tag + " " + t.id());
            }
            notes.add("exact lines are re-synced after every tick; the heartbeat stays as a safety net");
        }
        String content = String.join("\n", lines) + "\n";
        return new Plan(name(), List.of(), List.of(), List.of(new Plan.Command(List.of("crontab", "-"), content, false)), notes);
    }

    @Override
    public Plan uninstall(InstallRequest r, CommandRunner runner) throws IOException {
        String before = current(runner);
        List<String> lines = othersLines(before, tag(r));
        String content = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        if (content.equals(before)) return new Plan(name(), List.of(), List.of(), List.of(), List.of("no loom entries in the crontab"));
        return new Plan(name(), List.of(), List.of(), List.of(new Plan.Command(List.of("crontab", "-"), content, false)), List.of());
    }
}
