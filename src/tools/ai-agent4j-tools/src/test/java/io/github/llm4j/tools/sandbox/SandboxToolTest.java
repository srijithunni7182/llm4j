package io.github.llm4j.tools.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.FakeSandbox;
import io.github.llm4j.tools.SandboxBackend;
import io.github.llm4j.tools.SandboxKind;
import io.github.llm4j.tools.SandboxResult;
import io.github.llm4j.tools.support.FaultJournal;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 35, D5 to D8: the sandbox's contract, with a backend that never runs a program. */
class SandboxToolTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;
    final EffectJournal journal = EffectJournal.inMemory();
    final List<SandboxKind.Settings> made = new ArrayList<>();
    FakeSandbox fake = new FakeSandbox(Isolation.CONTAINER);
    RecordingEffects effects = new RecordingEffects(journal);
    final Map<String, String> secrets = effects.references;
    int steps;

    @BeforeEach
    void workspace() throws Exception {
        Files.createDirectories(dir.resolve("work/src"));
        Files.createDirectories(dir.resolve("work/tests"));
    }

    SandboxKind kind() {
        return new SandboxKind(s -> {
            made.add(s);
            return fake;
        });
    }

    Map<String, String> options(String... pairs) {
        Map<String, String> o =
                new HashMap<>(Map.of(
                        "workspace", "Data", "workspace.root", "work", "isolation", "container", "image", "python:3.12-slim",
                        "allow", "python3, ls"));
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) o.remove(pairs[i]);
            else o.put(pairs[i], pairs[i + 1]);
        }
        return o;
    }

    String check(Map<String, String> o) {
        return kind().check(o, dir);
    }

    Tool tool(Map<String, String> o) throws Exception {
        return kind().create("Py", o, dir, effects);
    }

    JsonNode call(Map<String, String> o, Object... args) throws Exception {
        return call(tool(o), args);
    }

    /** One call on a tool already built: a run builds its tools once. */
    JsonNode call(Tool t, Object... args) throws Exception {
        effects.step = "main/s" + (++steps);
        Map<String, Object> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out = t.execute(a);
        return out.startsWith("Error:") ? JSON.getNodeFactory().textNode(out) : JSON.readTree(out);
    }

    // ---- G41: no default isolation

    @Test
    void aSandboxWithoutAnIsolationLineIsALoadError() {
        assertThat(check(options("isolation", null))).contains("isolation").contains("no default");
        assertThat(check(options())).isNull();
        assertThat(check(options("isolation", "none"))).isNotNull();
    }

    // ---- G42: container never falls back

    @Test
    void aMissingContainerRuntimeIsALoadErrorThatNamesWhatToInstallAndNothingFallsBack() {
        fake.missing("the container runtime docker is not available: install Docker or Podman and start it");
        assertThat(check(options())).contains("install Docker or Podman");
        assertThat(made).allMatch(s -> s.isolation() == Isolation.CONTAINER);
        assertThat(fake.requests).isEmpty();
    }

    @Test
    void theStandardContainerBackendSaysWhatIsMissingWhenTheRuntimeIsNot() {
        Map<String, String> o = options("runtime", "podman");
        String problem = new SandboxKind(s -> new io.github.llm4j.tools.ContainerSandbox("no-such-runtime-xyz", s.image(), false)).check(o, dir);
        assertThat(problem).contains("no-such-runtime-xyz").contains("never falls back");
    }

    // ---- G43: the reported level is what counts

    @Test
    void aBackendBelowTheAskedLevelIsRefusedAtLoadAndARunReportedBelowItStops() throws Exception {
        fake = new FakeSandbox(Isolation.PROCESS);
        assertThat(check(options())).contains("applies process, below the container");
        fake = new FakeSandbox(Isolation.CONTAINER).answering(r -> new SandboxResult(0, false, "secret output", "", false, 0, Isolation.PROCESS, 1));
        JsonNode r = call(options(), "program", "ls");
        assertThat(r.asText()).contains("reported isolation process").contains("stopped").doesNotContain("secret output");
    }

    @Test
    void aScriptGivesArgumentsAsTheTextOfAJsonList() throws Exception {
        call(options(), "program", "python3", "args", "[\"-m\", \"pytest\", \"a b; rm -rf /\"]");
        assertThat(fake.requests.get(0).args()).containsExactly("-m", "pytest", "a b; rm -rf /");
    }

    @Test
    void theResultSaysTheIsolationTheBackendApplied() throws Exception {
        JsonNode r = call(options(), "program", "ls", "args", List.of("-l"));
        assertThat(r.get("isolation").asText()).isEqualTo("container");
        assertThat(r.get("exit").asInt()).isZero();
        assertThat(r.get("stdout").asText()).isEqualTo("ok");
        assertThat(fake.requests.get(0).args()).containsExactly("-l");
    }

    // ---- G44: an allow-listed program, an argument list, inside the workspace

    @Test
    void onlyAnAllowListedProgramRunsWithAnArgumentListInTheWorkspace() throws Exception {
        assertThat(call(options(), "program", "curl").asText()).contains("not allowed").contains("python3, ls");
        assertThat(call(options(), "program", "python3 -c 'import os'").asText()).contains("put its arguments in args");
        assertThat(call(options(), "program", "/usr/bin/python3").asText()).contains("is a name");
        assertThat(call(options(), "program", "python3", "args", "-c import os").asText()).contains("a list");
        assertThat(call(options(), "program", "python3", "args", "[\"-c\", 3, {}]").asText()).contains("strings only");
        assertThat(call(options(), "program", "python3", "args", "[\"-c\"").asText()).contains("not a JSON list");
        assertThat(call(options(), "program", "ls", "cwd", "../..").asText()).contains("outside the workspace");
        assertThat(call(options(), "program", "ls", "cwd", "nope").asText()).contains("not a folder");
        assertThat(fake.requests).isEmpty();
        call(options(), "program", "ls", "cwd", "src");
        assertThat(fake.requests.get(0).cwd()).isEqualTo("src");
        assertThat(check(options("allow", "python3 -m pip"))).contains("not a path or a command line");
    }

    // ---- G45: an empty environment, secrets by name, looked up per call

    @Test
    void theEnvironmentIsEmptyUnlessAVariableIsNamed() throws Exception {
        call(options(), "program", "ls");
        assertThat(fake.requests.get(0).env()).isEmpty();
    }

    @Test
    void aNamedSecretIsLookedUpForEachCallAndAppearsNowhere() throws Exception {
        String first = "sk-test-FIRSTcanary0000000000";
        String second = "sk-test-SECONDcanary000000000";
        secrets.put("secret.PYPI", first);
        fake = new FakeSandbox(Isolation.CONTAINER)
                .answering(r -> new SandboxResult(0, false, "token is " + r.env().get("PYPI_TOKEN"), "", false, 0, Isolation.CONTAINER, 1));
        Map<String, String> o = options("env.PYPI_TOKEN", "secret.PYPI");
        Tool t = tool(o);
        effects.step = "main/a";
        String r1 = t.execute(Map.of("program", "ls"));
        secrets.put("secret.PYPI", second);
        effects.step = "main/b";
        String r2 = t.execute(Map.of("program", "ls"));
        assertThat(fake.requests.get(0).env()).containsEntry("PYPI_TOKEN", first);
        assertThat(fake.requests.get(1).env()).as("looked up again, not kept").containsEntry("PYPI_TOKEN", second);
        assertThat(r1 + r2).doesNotContain("canary");
        assertThat(effects.everything()).doesNotContain("canary");
        assertThat(fake.requests.get(0).toString()).doesNotContain("canary");
        secrets.clear();
        effects.step = "main/c";
        assertThat(t.execute(Map.of("program", "ls"))).contains("secret.PYPI is not set").doesNotContain("canary");
        assertThat(check(options("env.BAD NAME", "secret.X"))).contains("not a variable name");
    }

    // ---- G47 and C3: a backend that cannot enforce a setting refuses it

    @Test
    void processIsolationRefusesWhatItCannotEnforce() {
        fake = new FakeSandbox(Isolation.PROCESS, EnumSet.of(SandboxBackend.Limit.TIME, SandboxBackend.Limit.OUTPUT));
        Map<String, String> process = options("isolation", "process", "image", null);
        assertThat(check(process)).contains("cannot keep a program off the network").contains("network: open");
        process.put("network", "allow");
        process.put("hosts", "pypi.org");
        assertThat(check(process)).contains("network: allow").contains("cannot");
        process.put("network", "open");
        process.remove("hosts");
        assertThat(check(process)).isNull();
        for (String limit : List.of("memory", "pids", "disk")) {
            Map<String, String> o = new HashMap<>(process);
            o.put(limit, limit.equals("pids") ? "50" : "64m");
            assertThat(check(o)).as(limit).contains(limit + ": process isolation cannot enforce it");
        }
        Map<String, String> guarded = new HashMap<>(process);
        guarded.put("workspace.protect", "tests/**");
        assertThat(check(guarded)).contains("tests/**").contains("cannot keep read only");
    }

    @Test
    void theNetworkSettingsAreChecked() {
        assertThat(check(options("network", "allow"))).contains("hosts");
        assertThat(check(options("network", "allow", "hosts", "10.0.0.1"))).contains("not a host name");
        assertThat(check(options("hosts", "pypi.org"))).contains("goes with network: allow");
        assertThat(check(options("network", "allow", "hosts", "pypi.org"))).isNull();
        assertThat(check(options("network", "wide"))).contains("network");
    }

    // ---- G49: limits are relaxed but bounded, and an agent can only lower them

    @Test
    void timeLimitsAreBoundedAndAnAgentCanOnlyLowerThem() throws Exception {
        assertThat(SandboxKind.DEFAULT_TIMEOUT).isEqualTo(Duration.ofMinutes(10));
        call(options(), "program", "ls");
        assertThat(fake.requests.get(0).limits().timeout()).isEqualTo(SandboxKind.DEFAULT_TIMEOUT);
        assertThat(check(options("timeout", "2h"))).contains("timeout").contains("at most");
        assertThat(check(options("timeout", "60m"))).isNull();
        call(options("timeout", "5m"), "program", "ls", "timeout", "50m");
        assertThat(fake.requests.get(1).limits().timeout()).isEqualTo(Duration.ofMinutes(5));
        call(options("timeout", "5m"), "program", "ls", "timeout", "30s");
        assertThat(fake.requests.get(2).limits().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(call(options(), "program", "ls", "timeout", "soon").asText()).contains("duration");
    }

    @Test
    void containerLimitsReachTheBackend() throws Exception {
        call(options("memory", "512m", "pids", "64", "disk", "100m", "max_output", "1k"), "program", "ls");
        var l = fake.requests.get(0).limits();
        assertThat(l.memoryBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(l.pids()).isEqualTo(64);
        assertThat(l.diskBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(l.maxOutput()).isEqualTo(1024);
        assertThat(check(options("image", null))).contains("image");
        assertThat(check(options("isolation", "process", "image", "x", "network", "open"))).contains("go with isolation: container");
    }

    @Test
    void protectedPathsAreMountedOneByOneOrAsAFolder() throws Exception {
        Files.writeString(dir.resolve("work/tests/test_a.py"), "x");
        Files.writeString(dir.resolve("work/src/oracle.json"), "x");
        call(options("workspace.protect", "tests/**, oracle.json"), "program", "ls");
        assertThat(fake.requests.get(0).protect()).containsExactlyInAnyOrder("tests", "src/oracle.json");
    }

    // ---- G51: a run is journaled and not repeated on resume

    @Test
    void aRunIsRecordedAndARestartedRunDoesNotRunItAgain() throws Exception {
        effects.step = "main/s1";
        tool(options()).execute(Map.of("program", "ls"));
        String again = tool(options()).execute(Map.of("program", "ls"));
        assertThat(fake.requests).hasSize(1);
        assertThat(again).startsWith("(already done");
    }

    @Test
    void aRunCutShortByACrashIsNotRepeatedBlindly() throws Exception {
        RecordingEffects crashing = new RecordingEffects(new FaultJournal(journal, 1, false));
        crashing.step = "main/s9";
        Tool t = kind().create("Py", options(), dir, crashing);
        assertThatThrownBy(() -> t.execute(Map.of("program", "ls"))).isInstanceOf(FaultJournal.SimulatedCrash.class);
        assertThat(fake.requests).hasSize(1);
        effects.step = "main/s9";
        String resumed = tool(options()).execute(Map.of("program", "ls"));
        assertThat(resumed).contains("outcome is unknown");
        assertThat(fake.requests).as("ran once").hasSize(1);
    }

    // ---- serve (D7), G52 and the lease of G53

    @Test
    void aServedProgramHasALeaseThatEndsWithStop() throws Exception {
        Tool o = tool(options("serve_port", "8000", "network", "open"));
        JsonNode s = call(o, "action", "serve", "program", "python3", "args", List.of("-m", "http.server", "8000"));
        String handle = s.get("handle").asText();
        assertThat(s.get("url").asText()).startsWith("http://127.0.0.1:").endsWith("/");
        assertThat(dir.resolve(".loom-workspace/Data/leases/" + handle + ".json")).exists();
        assertThat(call(o, "action", "status", "handle", handle).get("running").asBoolean()).isTrue();
        assertThat(call(o, "action", "serve", "program", "python3").asText()).contains("max_served: 1");
        call(o, "action", "stop", "handle", handle);
        assertThat(fake.stopped).contains(handle);
        assertThat(dir.resolve(".loom-workspace/Data/leases/" + handle + ".json")).doesNotExist();
        assertThat(call(o, "action", "status", "handle", handle).asText()).contains("no served program");
        assertThat(call(options(), "action", "serve", "program", "python3").asText()).contains("serve_port");
        assertThat(call(o, "action", "status", "handle", "../x").asText()).contains("not one this tool gave out");
    }

    @Test
    void whatACrashedRunLeftRunningIsStoppedBeforeAnyWork() throws Exception {
        Path leases = Files.createDirectories(dir.resolve(".loom-workspace/Data/leases"));
        Files.writeString(leases.resolve("llm4j-serve-old.json"), "{\"handle\":\"llm4j-serve-old\",\"owner\":\"Py\",\"port\":40001,\"deadline\":9999999999999}");
        Files.writeString(leases.resolve("other.json"), "{\"handle\":\"other\",\"owner\":\"Web\",\"port\":40002,\"deadline\":9999999999999}");
        fake.leftRunning("llm4j-serve-old");
        tool(options("serve_port", "8000", "network", "open"));
        assertThat(fake.stopped).containsExactly("llm4j-serve-old");
        assertThat(leases.resolve("llm4j-serve-old.json")).doesNotExist();
        assertThat(leases.resolve("other.json")).as("another tool's").exists();
        assertThat(effects.audited("served_leftover_stopped")).hasSize(1);
    }

    @Test
    void aLeasePastItsDeadlineIsStopped() throws Exception {
        Path leases = Files.createDirectories(dir.resolve(".loom-workspace/Data/leases"));
        Tool t = tool(options("serve_port", "8000", "network", "open"));
        Files.writeString(leases.resolve("late.json"), "{\"handle\":\"late\",\"owner\":\"Py\",\"port\":40003,\"deadline\":1}");
        effects.step = "main/x";
        t.execute(Map.of("action", "status", "handle", "late"));
        assertThat(fake.stopped).contains("late");
    }

    // ---- what the audit sees

    @Test
    void theProfileSaysWhatItReachesAndHowItIsIsolated() {
        ToolProfile none = new SandboxKind().profile(options());
        assertThat(none.isolation()).isEqualTo(Isolation.CONTAINER);
        assertThat(none.outward() || none.untrusted()).isFalse();
        assertThat(none.privateData()).isTrue();
        assertThat(none.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(none.journaled()).isTrue();
        assertThat(none.note()).contains("python3 can run any command inside the container").contains("network none");
        ToolProfile open = new SandboxKind().profile(options("network", "allow", "hosts", "pypi.org", "env.TOKEN", "secret.PYPI"));
        assertThat(open.outward() && open.untrusted()).isTrue();
        assertThat(open.note()).contains("allow (pypi.org)").contains("TOKEN from secret.PYPI");
        assertThat(new SandboxKind().profile(options("isolation", "process")).isolation()).isEqualTo(Isolation.PROCESS);
        assertThat(new SandboxKind().uncheckedResult(Map.of())).containsExactlyInAnyOrder("stdout", "stderr");
        assertThat(new SandboxKind().agentProblem(Map.of(), "Py", "Coder", false)).contains("approve");
        assertThat(new SandboxKind().agentProblem(Map.of("unattended", "true"), "Py", "Coder", false)).isNull();
    }

    @Test
    void anAgentSeesItsProgramsAndLimits() throws Exception {
        String d = tool(options("serve_port", "8000", "network", "open")).getDescription();
        assertThat(d).contains("python3, ls").contains("10m").contains("serve");
        assertThat(Set.copyOf(((Map<?, ?>) tool(options()).getParametersSchema().get("properties")).keySet().stream().map(String::valueOf).toList()))
                .contains("program", "args", "cwd", "handle");
    }
}
