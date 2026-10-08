package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.resume.TestClock;
import io.github.llm4j.loom.trigger.FileTriggerStore;
import io.github.llm4j.loom.trigger.Trigger;
import io.github.llm4j.loom.trigger.system.CommandRunner;
import io.github.llm4j.loom.trigger.system.Plan;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.ratelimit.RateLimitInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan E2E-3, E2E-4, E2E-5, V9.12 and the weave triggers / schedule commands. */
class WeaveResumeCliTest {

    @TempDir
    Path dir;

    final TestClock clock = new TestClock();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final List<Plan.Command> commands = new ArrayList<>();
    Instant limitedUntil;

    static final String SCRIPT = """
            budget { tokens: 100000 }
            agent Researcher { model: "test/model" system: "You are Researcher." }
            agent Writer { model: "test/model" system: "You are Writer." }
            workflow Main() {
                delegate "research" to Researcher -> research
                delegate "write about {research}" to Writer -> article
            }
            """;

    /** A fresh "process": its own model instance and call counter; only files are shared. */
    final class Process {
        final AtomicInteger calls = new AtomicInteger();
        final LLMClientFactory models = model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                if (system.contains("You are Writer.") && limitedUntil != null && clock.instant().isBefore(limitedUntil)) {
                    throw new RateLimitException(RateLimitInfo.at(limitedUntil, RateLimitInfo.Scope.DAILY_QUOTA, "google",
                            "daily"), clock.instant());
                }
                int n = calls.incrementAndGet();
                return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"answer" + n + "\"}\n```")
                        .model(model).tokenUsage(100, 50, 150).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };

        WeaveEnv env() {
            return new WeaveEnv(models, message -> "", new PrintStream(out, true), new PrintStream(err, true), clock,
                    d -> clock.advance(d), fakeCommands(), List.of("/usr/local/bin/weave"));
        }
    }

    CommandRunner fakeCommands() {
        return c -> {
            commands.add(c);
            if (c.argv().equals(List.of("id", "-u"))) return new CommandRunner.Result(0, "1000\n", "");
            if (c.argv().equals(List.of("crontab", "-l"))) return new CommandRunner.Result(1, "", "no crontab");
            return new CommandRunner.Result(0, "", "");
        };
    }

    File script(String source) throws Exception {
        Path f = dir.resolve("job.loom");
        Files.writeString(f, source);
        return f.toFile();
    }

    String output() {
        return out.toString();
    }

    @Test
    void e2e3_pauseThenASeparateTickResumes() throws Exception {
        limitedUntil = Instant.parse("2026-09-28T07:00:00Z");
        Path runDir = dir.resolve("runs/job-1");
        Path store = dir.resolve("store");
        Process first = new Process();
        int exit = WeaveCLI.run(script(SCRIPT), null, "Main", Map.of(), null, null, null, null, runDir, store, false, first.env());
        assertThat(exit).isEqualTo(4);
        assertThat(output()).contains("⏸ Paused: google daily quota. Resumes at 2026-09-28 07:00 UTC (in 21h)")
                .contains("No system trigger is installed").contains("weave triggers install").contains("weave resume " + runDir);
        assertThat(runDir.resolve("run.json")).exists();
        assertThat(runDir.resolve("journal.json")).exists();
        FileTriggerStore triggers = new FileTriggerStore(store);
        Trigger resume = triggers.get(Trigger.resumeId(runDir.toString())).orElseThrow();
        assertThat(resume.nextFire()).isBetween(limitedUntil, limitedUntil.plusSeconds(30));
        assertThat(first.calls.get()).isEqualTo(1);

        // Hours later, cron runs `weave tick` in a new process.
        out.reset();
        clock.set(resume.nextFire());
        Process later = new Process();
        assertThat(WeaveCLI.tick(store, later.env())).isZero();
        assertThat(later.calls.get()).isEqualTo(1); // only the Writer: research replayed from the journal
        assertThat(output()).contains("⏯  Resuming").contains("✅ Workflow completed successfully").contains("💸 Spend")
                .contains("🔔 Fired 1 trigger(s)");
        assertThat(triggers.all()).isEmpty();
    }

    @Test
    void e2e3b_resumeNowAndDefaultStore() throws Exception {
        limitedUntil = Instant.parse("2026-09-27T12:00:00Z");
        Path runDir = dir.resolve("runs/job-2");
        assertThat(WeaveCLI.run(script(SCRIPT), null, "Main", Map.of(), null, null, null, null, runDir, null, false,
                new Process().env())).isEqualTo(4);
        Path store = dir.resolve("runs/.loom-triggers");
        assertThat(new FileTriggerStore(store).all()).hasSize(1);
        // still limited: resuming now pauses again and keeps (replaces) the trigger
        assertThat(WeaveCLI.resume(runDir, new Process().env())).isEqualTo(4);
        assertThat(new FileTriggerStore(store).all()).hasSize(1);
        clock.set(limitedUntil);
        assertThat(WeaveCLI.resume(runDir, new Process().env())).isZero();
        assertThat(new FileTriggerStore(store).all()).isEmpty();
        assertThat(WeaveCLI.resume(dir.resolve("nowhere"), new Process().env())).isEqualTo(2);
    }

    @Test
    void e2e4_waitStaysAliveAndFinishes() throws Exception {
        limitedUntil = TestClock.T0.plusSeconds(3600); // beyond the 30 s inline wait
        Path runDir = dir.resolve("runs/job-3");
        int exit = WeaveCLI.run(script(SCRIPT), null, "Main", Map.of(), null, null, null, null, runDir, null, true,
                new Process().env());
        assertThat(exit).isZero();
        assertThat(output()).contains("⏸ Paused").contains("⏳ Waiting 1h").contains("✅ Workflow completed successfully");
    }

    @Test
    void v9_12_tickWithNothingDueIsQuiet() throws Exception {
        Path store = dir.resolve("store");
        assertThat(WeaveCLI.tick(store, new Process().env())).isZero();
        assertThat(output()).isEmpty();
        FileTriggerStore s = new FileTriggerStore(store);
        s.upsert(Trigger.resume(dir.resolve("gone").toString(), TestClock.T0, "x", 1));
        try (AutoCloseable held = s.tickLock()) {
            assertThat(WeaveCLI.tick(store, new Process().env())).isZero(); // another tick is running
            assertThat(s.all()).hasSize(1);
        }
    }

    @Test
    void e2e5_scheduledWorkflowOnCron() throws Exception {
        File script = script("""
                agent Writer { model: "test/model" system: "You are Writer." }
                workflow Digest(topic) {
                    delegate "digest {topic}" to Writer -> digest
                }
                schedule Often { cron: "*/5 * * * *" run: Digest(topic="agents") }
                """);
        Path store = dir.resolve("store");
        assertThat(Triggers.syncSchedules(script.toPath(), store, new Process().env())).isZero();
        assertThat(output()).contains("✓ schedule:" + script.getAbsolutePath() + "/Often  cron \"*/5 * * * *\" UTC  next: 2026-09-27 10:05 UTC");
        FileTriggerStore triggers = new FileTriggerStore(store);

        clock.set("2026-09-27T10:05:00Z");
        Process p = new Process();
        WeaveCLI.tick(store, p.env());
        assertThat(p.calls.get()).isEqualTo(1);

        limitedUntil = Instant.parse("2026-09-27T10:12:00Z");
        clock.set("2026-09-27T10:10:00Z");
        WeaveCLI.tick(store, p.env());
        String paused = Trigger.resumeId("Often@2026-09-27T10:10:00Z");
        assertThat(triggers.get(paused)).isPresent();

        clock.set("2026-09-27T10:15:00Z"); // the paused run's resume (10:12+) comes first, then the 10:15 slot
        WeaveCLI.tick(store, p.env());
        assertThat(triggers.get(paused)).isEmpty();
        assertThat(p.calls.get()).isEqualTo(3);
        assertThat(Runs.scheduledRunDir(store, "Often@2026-09-27T10:15:00Z").resolve("journal.json")).exists();
        assertThat(triggers.get("schedule:" + script.getAbsolutePath() + "/Often").orElseThrow().nextFire())
                .isEqualTo(Instant.parse("2026-09-27T10:20:00Z"));
    }

    @Test
    void scheduledAgentTasksRunFromTick() throws Exception {
        File script = script("""
                agent AdminBot { model: "test/model" system: "You are AdminBot." }
                schedule Cleanup { every: 1h agent: AdminBot task: "purge" }
                """);
        Path store = dir.resolve("store");
        Triggers.syncSchedules(script.toPath(), store, new Process().env());
        clock.advance(Duration.ofHours(1));
        Process p = new Process();
        WeaveCLI.tick(store, p.env());
        assertThat(p.calls.get()).isEqualTo(1);
        assertThat(output()).contains("⏰ AdminBot: purge");
    }

    @Test
    void triggersListPauseFireCancel() throws Exception {
        Path store = dir.resolve("store");
        FileTriggerStore s = new FileTriggerStore(store);
        s.upsert(Trigger.resume(dir.resolve("gone").toString(), TestClock.T0.plusSeconds(600), "google daily quota", 1));
        String id = Trigger.resumeId(dir.resolve("gone").toString());
        WeaveEnv env = new Process().env();
        Triggers.InstallOptions none = new Triggers.InstallOptions(null, "heartbeat", "5m", false, null, null, null, null, dir, "Linux");
        assertThat(Triggers.run("list", store, null, none, env)).isZero();
        assertThat(output()).contains("● " + id).contains("next: 2026-09-27 10:10 UTC").contains("why:  google daily quota")
                .contains("No system trigger installed");
        assertThat(Triggers.run("pause", store, id, none, env)).isZero();
        assertThat(s.get(id).orElseThrow().enabled()).isFalse();
        assertThat(Triggers.run("enable", store, id, none, env)).isZero();
        assertThat(Triggers.run("fire", store, id, none, env)).isZero(); // fires now: its run dir is gone, so it fails
        assertThat(s.get(id)).isEmpty();
        s.upsert(Trigger.resume("x", TestClock.T0, "x", 1));
        assertThat(Triggers.run("cancel", store, "resume:x", none, env)).isZero();
        assertThat(s.all()).isEmpty();
        assertThat(Triggers.run("pause", store, null, none, env)).isEqualTo(2);
        assertThat(Triggers.run("pause", store, "missing", none, env)).isEqualTo(2);
        assertThat(Triggers.run("explode", store, null, none, env)).isEqualTo(2);
        out.reset();
        assertThat(Triggers.run("list", dir.resolve("empty"), null, none, env)).isZero();
        assertThat(output()).contains("No triggers");
    }

    @Test
    void installShowsThenAppliesThenTheMessageKnowsAboutIt() throws Exception {
        Path store = dir.resolve("store");
        WeaveEnv env = new Process().env();
        Triggers.InstallOptions dry = new Triggers.InstallOptions("systemd", "heartbeat", "5m", false, null, null, null, null,
                dir.resolve("home"), "Linux");
        assertThat(Triggers.run("install", store, null, dry, env)).isZero();
        assertThat(output()).contains("write  " + dir.resolve("home/.config/systemd/user")).contains("Nothing changed");
        assertThat(commands).extracting(Plan.Command::argv).noneMatch(a -> a.get(0).equals("systemctl"));
        assertThat(dir.resolve("home")).doesNotExist();

        Triggers.InstallOptions apply = new Triggers.InstallOptions("systemd", "heartbeat", "5m", true, null, null, null, null,
                dir.resolve("home"), "Linux");
        assertThat(Triggers.run("install", store, null, apply, env)).isZero();
        assertThat(commands).extracting(Plan.Command::argv).anyMatch(a -> a.contains("enable"));
        assertThat(store.resolve("system.json")).exists();
        assertThat(output()).contains("✓ Installed: systemd").contains("--env-file");

        limitedUntil = Instant.parse("2026-09-28T07:00:00Z");
        out.reset();
        WeaveCLI.run(script(SCRIPT), null, "Main", Map.of(), null, null, null, null, dir.resolve("runs/j"), store, false, env);
        assertThat(output()).contains("A system trigger (systemd, heartbeat every 5m) will resume it.");

        assertThat(Triggers.run("uninstall", store, null, apply, env)).isZero();
        assertThat(store.resolve("system.json")).doesNotExist();
        assertThat(dir.resolve("home/.config/systemd/user").toFile().list()).isEmpty();
    }

    @Test
    void exactModeResyncsAfterEveryTick() throws Exception {
        Path store = dir.resolve("store");
        WeaveEnv env = new Process().env();
        Triggers.InstallOptions exact = new Triggers.InstallOptions("systemd", "exact", "5m", true, null, null, null, null,
                dir.resolve("home"), "Linux");
        FileTriggerStore s = new FileTriggerStore(store);
        s.upsert(Trigger.resume(dir.resolve("gone").toString(), TestClock.T0.plusSeconds(60), "x", 1));
        assertThat(Triggers.run("install", store, null, exact, env)).isZero();
        Path units = dir.resolve("home/.config/systemd/user");
        assertThat(units.toFile().list()).hasSize(3); // service, heartbeat timer, one exact timer
        clock.advance(Duration.ofMinutes(2));
        WeaveCLI.tick(store, env); // fires (and drops) the trigger; its exact timer goes too
        assertThat(units.toFile().list()).hasSize(2);
    }

    @Test
    void daemonFiresWhileRunning() throws Exception {
        Path store = dir.resolve("store");
        new FileTriggerStore(store).upsert(Trigger.resume(dir.resolve("gone").toString(), TestClock.T0, "x", 1));
        assertThat(WeaveCLI.daemon(store, Duration.ofMillis(20), Duration.ofMillis(300), new Process().env())).isZero();
        assertThat(new FileTriggerStore(store).all()).isEmpty();
        assertThat(output()).contains("👂 Watching");
    }
}
