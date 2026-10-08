package io.github.llm4j.loom.trigger.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.resume.TestClock;
import io.github.llm4j.loom.trigger.Trigger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Branches of the backends not covered by the main plan tests (coverage for N4). */
class BackendEdgeCasesTest {

    @TempDir
    Path tmp;

    final List<Trigger> pending = List.of(
            Trigger.resume("on", Instant.parse("2026-09-28T07:00:30Z"), "x", 1),
            Trigger.resume("off", TestClock.T0, "x", 1).withEnabled(false));

    InstallRequest request(Duration every, Path envFile, String uid) {
        return new InstallRequest(tmp.resolve("store"), List.of("weave"), InstallRequest.Mode.EXACT, every, pending,
                tmp.resolve("home"), ZoneOffset.UTC, envFile, uid, null, null, null);
    }

    @Test
    void launchdWithEnvFileDefaultUidAndRounding() throws Exception {
        Plan plan = new LaunchdBackend().install(request(null, Path.of("/k/env"), null), c -> new CommandRunner.Result(0, "", ""));
        String plist = plan.writes().get(0).content();
        assertThat(plist).contains("<string>/bin/sh</string>", "<string>-c</string>", "set -a; . /k/env; exec weave tick")
                .contains("<key>Minute</key><integer>1</integer>") // 07:00:30 rounds up to 07:01
                .doesNotContain("<integer>10</integer></dict>"); // the paused trigger is left out
        assertThat(plan.commands().get(1).argv()).contains("gui/501");
        assertThat(new LaunchdBackend().uninstall(request(null, null, "777"), c -> null).commands().get(0).argv())
                .contains("gui/777/dev.llm4j.loom." + request(null, null, null).storeId());
    }

    @Test
    void systemdAndWindowsSkipPausedTriggersAndNoteEnvFiles() throws Exception {
        CommandRunner ok = c -> new CommandRunner.Result(0, "", "");
        Plan systemd = new SystemdBackend().install(request(Duration.ofMinutes(30), null, null), ok);
        assertThat(systemd.writes()).hasSize(3); // service, heartbeat, one exact timer (paused one skipped)
        assertThat(systemd.writes().get(1).content()).contains("OnCalendar=*:0/30");
        Plan windows = new WindowsBackend().install(request(null, Path.of("/k/env"), null), ok);
        assertThat(windows.notes()).anySatisfy(n -> assertThat(n).contains("environment variables"));
        assertThat(windows.writes()).hasSize(1);
        Plan failedQuery = new WindowsBackend().uninstall(request(null, null, null), c -> new CommandRunner.Result(1, "", "x"));
        assertThat(failedQuery.commands()).hasSize(1);
        assertThat(new SystemdBackend().uninstall(request(null, null, null), ok).deletes()).hasSize(2); // nothing exact on disk
    }

    @Test
    void planDescriptionShowsEverything() {
        Plan plan = new Plan("x", List.of(new Plan.FileWrite(Path.of("/f"), "a\nb")), List.of(Path.of("/g")),
                List.of(new Plan.Command(List.of("crontab", "-"), "line1\nline2", false), Plan.Command.of("echo", "it's")),
                List.of("n"));
        assertThat(plan.describe()).contains("write  /f", "| a", "| b", "delete /g", "run    crontab - < (new content)",
                "| line1", "run    echo 'it'\\''s'", "note   n");
        assertThat(plan.isEmpty()).isFalse();
    }

    @Test
    void systemCommandRunnerRunsRealProcesses() throws Exception {
        CommandRunner.Result r = CommandRunner.SYSTEM.run(new Plan.Command(List.of("cat"), "hello", false));
        assertThat(r.exit()).isZero();
        assertThat(r.stdout()).isEqualTo("hello");
        assertThat(CommandRunner.SYSTEM.run(Plan.Command.of("sh", "-c", "echo oops >&2; exit 3")))
                .isEqualTo(new CommandRunner.Result(3, "", "oops\n"));
    }
}
