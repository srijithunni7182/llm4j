package io.github.llm4j.loom.trigger.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.loom.resume.TestClock;
import io.github.llm4j.loom.trigger.Trigger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V9.1–V9.11: each backend's plan, checked without touching the machine. */
class SystemTriggerTest {

    @TempDir
    Path tmp;

    /** Records commands; answers {@code crontab -l} from a fake crontab that {@code crontab -} replaces. */
    static final class FakeRunner implements CommandRunner {
        final List<Plan.Command> ran = new ArrayList<>();
        String crontab; // null: "no crontab for user"
        String schtasks = "";

        FakeRunner(String crontab) {
            this.crontab = crontab;
        }

        @Override
        public Result run(Plan.Command c) {
            ran.add(c);
            if (c.argv().equals(List.of("crontab", "-l"))) {
                return crontab == null ? new Result(1, "", "no crontab for user") : new Result(0, crontab, "");
            }
            if (c.argv().equals(List.of("crontab", "-"))) {
                crontab = c.stdin();
                return new Result(0, "", "");
            }
            if (c.argv().get(0).equals("schtasks") && c.argv().get(1).equals("/Query")) return new Result(0, schtasks, "");
            return new Result(0, "", "");
        }

        List<List<String>> changing() {
            return ran.stream().map(Plan.Command::argv).filter(a -> !a.equals(List.of("crontab", "-l"))).toList();
        }
    }

    private InstallRequest request(Path store, InstallRequest.Mode mode, List<Trigger> pending) {
        return new InstallRequest(store, List.of("/opt/loom/bin/weave"), mode, Duration.ofMinutes(5), pending,
                tmp.resolve("home"), ZoneOffset.UTC, null, "501", null, null, null);
    }

    private Path store() {
        return tmp.resolve("my runs/.loom-triggers");
    }

    @Test
    void v9_1_cronHeartbeatKeepsOtherLinesAndIsIdempotent() throws IOException {
        String original = "MAILTO=me@example.com\n0 3 * * * /usr/bin/backup\n";
        FakeRunner runner = new FakeRunner(original);
        InstallRequest r = request(store(), InstallRequest.Mode.HEARTBEAT, List.of());
        CronBackend cron = new CronBackend();

        SystemTriggers.apply(cron.install(r, runner), runner);
        String id = r.storeId();
        assertThat(runner.crontab).isEqualTo(original
                + "*/5 * * * * /opt/loom/bin/weave tick '" + store().toAbsolutePath() + "' # loom:" + id + "\n");
        String once = runner.crontab;
        SystemTriggers.apply(cron.install(r, runner), runner);
        assertThat(runner.crontab).isEqualTo(once); // byte-identical

        SystemTriggers.apply(cron.uninstall(r, runner), runner);
        assertThat(runner.crontab).isEqualTo(original); // exactly as before
        assertThat(cron.uninstall(r, runner).isEmpty()).isTrue();
    }

    @Test
    void v9_1b_cronWithNoCrontabAndAnEnvFile() throws IOException {
        FakeRunner runner = new FakeRunner(null);
        InstallRequest r = new InstallRequest(store(), List.of("weave"), null, Duration.ofMinutes(90), List.of(),
                tmp, ZoneOffset.UTC, Path.of("/home/me/.loom/env"), null, null, null, null);
        SystemTriggers.apply(new CronBackend().install(r, runner), runner);
        assertThat(runner.crontab).isEqualTo("0 */1 * * * /bin/sh -c 'set -a; . /home/me/.loom/env; exec weave tick '\\''"
                + store().toAbsolutePath() + "'\\''' # loom:" + r.storeId() + "\n");
        SystemTriggers.apply(new CronBackend().uninstall(r, runner), runner);
        assertThat(runner.crontab).isEmpty();
    }

    @Test
    void v9_2_cronExactAddsOneLinePerPendingTrigger() throws IOException {
        List<Trigger> pending = List.of(
                Trigger.resume("runs/a", Instant.parse("2026-09-28T07:00:00Z"), "x", 1),
                Trigger.resume("runs/b", Instant.parse("2026-09-27T10:30:20Z"), "x", 1), // rounds up to 10:31
                Trigger.resume("runs/off", Instant.parse("2026-09-27T11:00:00Z"), "x", 1).withEnabled(false));
        FakeRunner runner = new FakeRunner("");
        InstallRequest r = request(store(), InstallRequest.Mode.EXACT, pending);
        Plan plan = new CronBackend().install(r, runner);
        SystemTriggers.apply(plan, runner);
        String tag = "# loom:" + r.storeId();
        assertThat(runner.crontab.split("\n")).containsExactly(
                "*/5 * * * * /opt/loom/bin/weave tick '" + store().toAbsolutePath() + "' " + tag,
                "0 7 28 9 * /opt/loom/bin/weave tick '" + store().toAbsolutePath() + "' " + tag + " resume:runs/a",
                "31 10 27 9 * /opt/loom/bin/weave tick '" + store().toAbsolutePath() + "' " + tag + " resume:runs/b");
        assertThat(plan.notes()).anySatisfy(n -> assertThat(n).contains("re-synced"));
        // re-sync with only one pending trigger drops the other line
        SystemTriggers.apply(new CronBackend().install(request(store(), InstallRequest.Mode.EXACT, pending.subList(0, 1)), runner), runner);
        assertThat(runner.crontab.split("\n")).hasSize(2);
    }

    @Test
    void v9_3_systemdUserTimer() throws IOException {
        InstallRequest r = request(store(), InstallRequest.Mode.HEARTBEAT, List.of());
        Plan plan = new SystemdBackend().install(r, new FakeRunner(""));
        Path units = tmp.resolve("home/.config/systemd/user");
        String unit = "loom-" + r.storeId();
        assertThat(plan.writes()).extracting(Plan.FileWrite::path)
                .containsExactly(units.resolve(unit + ".service"), units.resolve(unit + ".timer"));
        assertThat(plan.writes().get(0).content()).isEqualTo("""
                [Unit]
                Description=Loom triggers for %s

                [Service]
                Type=oneshot
                ExecStart=/opt/loom/bin/weave tick "%s"
                """.formatted(store().toAbsolutePath(), store().toAbsolutePath()));
        assertThat(plan.writes().get(1).content()).isEqualTo("""
                [Unit]
                Description=Wake Loom every 5m for %s

                [Timer]
                OnCalendar=*:0/5
                Persistent=true
                Unit=%s.service

                [Install]
                WantedBy=timers.target
                """.formatted(store().toAbsolutePath(), unit));
        assertThat(plan.commands()).extracting(Plan.Command::argv).containsExactly(
                List.of("systemctl", "--user", "daemon-reload"),
                List.of("systemctl", "--user", "enable", "--now", unit + ".timer"));

        FakeRunner runner = new FakeRunner("");
        SystemTriggers.apply(plan, runner);
        assertThat(Files.readString(units.resolve(unit + ".timer"))).contains("Persistent=true");
        Plan removal = new SystemdBackend().uninstall(r, runner);
        SystemTriggers.apply(removal, runner);
        assertThat(units.resolve(unit + ".timer")).doesNotExist();
        assertThat(units.resolve(unit + ".service")).doesNotExist();
    }

    @Test
    void v9_3b_systemdExactTimersAndEnvFile() throws IOException {
        List<Trigger> pending = List.of(Trigger.resume("r1", Instant.parse("2026-09-28T07:00:00Z"), "x", 1));
        InstallRequest r = new InstallRequest(store(), List.of("weave"), InstallRequest.Mode.EXACT, Duration.ofHours(2),
                pending, tmp.resolve("home"), ZoneOffset.UTC, Path.of("/home/me/.loom/env"), null, null, null, null);
        FakeRunner runner = new FakeRunner("");
        Plan plan = new SystemdBackend().install(r, runner);
        assertThat(plan.writes()).hasSize(3);
        assertThat(plan.writes().get(0).content()).contains("EnvironmentFile=/home/me/.loom/env\n");
        assertThat(plan.writes().get(1).content()).contains("OnCalendar=*-*-* 0/2:00:00");
        assertThat(plan.writes().get(2).content()).contains("OnCalendar=2026-09-28 07:00:00 UTC");
        SystemTriggers.apply(plan, runner);
        // the trigger fired: re-sync removes its timer
        Plan resync = new SystemdBackend().install(new InstallRequest(store(), List.of("weave"), InstallRequest.Mode.EXACT,
                Duration.ofHours(2), List.of(), tmp.resolve("home"), ZoneOffset.UTC, null, null, null, null, null), runner);
        assertThat(resync.deletes()).singleElement().satisfies(p -> assertThat(p.toString()).endsWith(".timer"));
        assertThat(new SystemdBackend().uninstall(r, runner).deletes()).hasSize(3);
    }

    @Test
    void v9_4_launchdAgent() throws IOException {
        List<Trigger> pending = List.of(Trigger.resume("r1", Instant.parse("2026-09-28T07:00:00Z"), "x", 1));
        InstallRequest r = request(store(), InstallRequest.Mode.EXACT, pending);
        Plan plan = new LaunchdBackend().install(r, new FakeRunner(""));
        String label = "dev.llm4j.loom." + r.storeId();
        assertThat(plan.writes()).singleElement().satisfies(w -> {
            assertThat(w.path()).isEqualTo(tmp.resolve("home/Library/LaunchAgents/" + label + ".plist"));
            assertThat(w.content()).contains("<string>" + label + "</string>", "<string>/opt/loom/bin/weave</string>",
                    "<string>tick</string>", "<string>" + store().toAbsolutePath() + "</string>",
                    "<key>StartInterval</key>\n  <integer>300</integer>",
                    "<dict><key>Month</key><integer>9</integer><key>Day</key><integer>28</integer><key>Hour</key><integer>7</integer><key>Minute</key><integer>0</integer></dict>");
        });
        assertThat(plan.commands()).extracting(Plan.Command::argv).containsExactly(
                List.of("launchctl", "bootout", "gui/501/" + label),
                List.of("launchctl", "bootstrap", "gui/501", tmp.resolve("home/Library/LaunchAgents/" + label + ".plist").toString()));
        assertThat(plan.commands().get(0).mayFail()).isTrue();
        assertThat(new LaunchdBackend().uninstall(r, new FakeRunner("")).deletes()).hasSize(1);
    }

    @Test
    void v9_5_windowsTask() throws IOException {
        List<Trigger> pending = List.of(Trigger.resume("r1", Instant.parse("2026-09-28T07:00:00Z"), "x", 1));
        InstallRequest r = request(store(), InstallRequest.Mode.EXACT, pending);
        Plan plan = new WindowsBackend().install(r, new FakeRunner(""));
        String task = "\\Loom\\" + r.storeId();
        assertThat(plan.commands().get(0).argv()).containsExactly("schtasks", "/Create", "/TN", task, "/SC", "MINUTE",
                "/MO", "5", "/TR", "/opt/loom/bin/weave tick \"" + store().toAbsolutePath() + "\"", "/F");
        assertThat(plan.writes()).singleElement().satisfies(w ->
                assertThat(w.content()).contains("<StartBoundary>2026-09-28T07:00:00Z</StartBoundary>"));
        FakeRunner runner = new FakeRunner("");
        runner.schtasks = "\"" + task + "-abcd1234\",\"N/A\",\"Ready\"\n\"\\Other\\Task\",\"N/A\",\"Ready\"\n";
        assertThat(new WindowsBackend().uninstall(r, runner).commands()).extracting(Plan.Command::argv).containsExactly(
                List.of("schtasks", "/Delete", "/TN", task + "-abcd1234", "/F"),
                List.of("schtasks", "/Delete", "/TN", task, "/F"));
    }

    @Test
    void v9_6_cloudSchedulerCommandIsPrinted() {
        InstallRequest r = new InstallRequest(store(), List.of("weave"), null, null, List.of(), tmp, ZoneOffset.UTC, null,
                null, "https://studio.example.run.app/", "loom-tick@proj.iam.gserviceaccount.com", "asia-south1");
        Plan plan = new CloudSchedulerBackend().install(r, new FakeRunner(""));
        assertThat(plan.commands().get(1).argv()).containsExactly("gcloud", "scheduler", "jobs", "create", "http",
                "loom-" + r.storeId(), "--location=asia-south1", "--schedule=*/5 * * * *",
                "--uri=https://studio.example.run.app/loom/tick", "--http-method=POST",
                "--oidc-service-account-email=loom-tick@proj.iam.gserviceaccount.com",
                "--oidc-token-audience=https://studio.example.run.app");
        assertThat(plan.describe()).contains("gcloud scheduler jobs create http");
        InstallRequest noAccount = new InstallRequest(store(), List.of("weave"), InstallRequest.Mode.EXACT, null, List.of(),
                tmp, ZoneOffset.UTC, null, null, "https://x", null, null);
        assertThat(new CloudSchedulerBackend().install(noAccount, new FakeRunner("")).notes())
                .anySatisfy(n -> assertThat(n).contains("X-Loom-Token")).anySatisfy(n -> assertThat(n).contains("heartbeat only"));
        assertThatThrownBy(() -> new CloudSchedulerBackend().install(request(store(), null, List.of()), new FakeRunner("")))
                .hasMessageContaining("--url");
        assertThat(new CloudSchedulerBackend().uninstall(r, new FakeRunner("")).commands()).hasSize(1);
    }

    @Test
    void v9_7_planningChangesNothing() throws IOException {
        FakeRunner runner = new FakeRunner("0 3 * * * backup\n");
        for (String name : SystemTriggers.NAMES) {
            InstallRequest r = new InstallRequest(store(), List.of("weave"), InstallRequest.Mode.EXACT, null,
                    List.of(Trigger.resume("r", TestClock.T0, "x", 1)), tmp.resolve("home"), ZoneOffset.UTC, null,
                    "501", "https://x", null, null);
            SystemTriggerBackend backend = SystemTriggers.backend(name);
            assertThat(backend.name()).isEqualTo(name);
            backend.install(r, runner);
            backend.uninstall(r, runner);
        }
        assertThat(runner.changing()).allSatisfy(argv -> assertThat(argv.get(1)).isIn("/Query")); // reads only
        assertThat(tmp.resolve("home")).doesNotExist();
        assertThatThrownBy(() -> SystemTriggers.backend("at")).hasMessageContaining("Unknown backend");
    }

    @Test
    void v9_8_userLevelOnlyAndQuoted() throws IOException {
        Path spaced = tmp.resolve("it's my store");
        for (String name : SystemTriggers.NAMES) {
            InstallRequest r = new InstallRequest(spaced, List.of("/opt/my tools/weave"), InstallRequest.Mode.EXACT, null,
                    List.of(Trigger.resume("r", TestClock.T0, "x", 1)), tmp.resolve("home"), ZoneOffset.UTC, null, "501",
                    "https://x", "sa@x", null);
            Plan plan = SystemTriggers.backend(name).install(r, new FakeRunner(""));
            for (Plan.FileWrite w : plan.writes()) {
                assertThat(w.path().startsWith(tmp.resolve("home")) || w.path().startsWith(spaced)).as(w.path().toString()).isTrue();
            }
            for (Plan.Command c : plan.commands()) assertThat(c.argv()).doesNotContain("sudo");
        }
        FakeRunner runner = new FakeRunner("");
        InstallRequest r = new InstallRequest(spaced, List.of("/opt/my tools/weave"), null, null, List.of(), tmp,
                ZoneOffset.UTC, null, null, null, null, null);
        SystemTriggers.apply(new CronBackend().install(r, runner), runner);
        assertThat(runner.crontab).contains("'/opt/my tools/weave' tick '" + spaced.toAbsolutePath().toString().replace("'", "'\\''") + "'");
    }

    @Test
    void failingCommandsStopTheApplyUnlessBestEffort() {
        CommandRunner failing = c -> new CommandRunner.Result(1, "", "boom");
        Plan plan = new Plan("x", List.of(), List.of(), List.of(Plan.Command.bestEffort("a"), Plan.Command.of("b")), List.of());
        assertThatThrownBy(() -> SystemTriggers.apply(plan, failing)).hasMessageContaining("'b' failed (exit 1): boom");
        assertThat(new Plan("x", List.of(), List.of(), List.of(), List.of()).describe()).contains("nothing to change");
    }

    @Test
    void detection() {
        assertThat(SystemTriggers.detect("Windows 11", c -> null)).isEqualTo("windows");
        assertThat(SystemTriggers.detect("Mac OS X", c -> null)).isEqualTo("launchd");
        assertThat(SystemTriggers.detect("Linux", c -> new CommandRunner.Result(0, "", ""))).isEqualTo("systemd");
        assertThat(SystemTriggers.detect("Linux", c -> new CommandRunner.Result(1, "", ""))).isEqualTo("cron");
        assertThat(SystemTriggers.detect("Linux", c -> { throw new IOException("no systemctl"); })).isEqualTo("cron");
    }

    @Test
    void v9_11_realCrontabRoundTrip() throws Exception {
        boolean hasCrontab;
        try {
            hasCrontab = new ProcessBuilder("sh", "-c", "command -v crontab").start().waitFor() == 0;
        } catch (IOException e) {
            hasCrontab = false;
        }
        assumeTrue(hasCrontab, "crontab is not installed");
        // Never touch the real user's crontab: only run where a crontab can't exist yet for a throwaway HOME.
        assumeTrue(Boolean.getBoolean("loom.realCrontabTest"), "set -Dloom.realCrontabTest=true to run against the real crontab");
        InstallRequest r = new InstallRequest(store(), List.of("weave"), null, null, List.of(), tmp, ZoneOffset.UTC,
                null, null, null, null, null);
        SystemTriggers.apply(new CronBackend().install(r, CommandRunner.SYSTEM), CommandRunner.SYSTEM);
        assertThat(CronBackend.current(CommandRunner.SYSTEM)).contains("# loom:" + r.storeId());
        SystemTriggers.apply(new CronBackend().uninstall(r, CommandRunner.SYSTEM), CommandRunner.SYSTEM);
        assertThat(CronBackend.current(CommandRunner.SYSTEM)).doesNotContain("# loom:" + r.storeId());
    }

    @Test
    void storeIdIsStable() {
        assertThat(request(store(), null, List.of()).storeId()).isEqualTo(request(store(), null, List.of()).storeId()).hasSize(8);
        assertThat(Map.of()).isEmpty();
    }
}
