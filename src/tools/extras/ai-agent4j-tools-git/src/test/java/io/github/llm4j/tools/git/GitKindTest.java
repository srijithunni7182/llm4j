package io.github.llm4j.tools.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.ratelimit.Sleeper;
import io.github.llm4j.tools.FakeSandbox;
import io.github.llm4j.tools.SandboxRequest;
import io.github.llm4j.tools.SandboxResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 35, D18: the git kind on a fake sandbox, so no git and no container are needed. */
class GitKindTest {

    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;
    FakeSandbox fake = new FakeSandbox(Isolation.CONTAINER);
    final EffectJournal journal = EffectJournal.inMemory();
    final List<String> traced = new ArrayList<>();
    final Map<String, String> secrets = new HashMap<>();
    String step = "main/s1";

    final EffectContext context = new EffectContext() {
        public void audit(String event, Map<String, Object> data) {}
        public void trace(String text, Map<String, Object> data) { traced.add(text); }
        public EffectJournal journal() { return journal; }
        public String currentStep() { return step; }
        public long attempt() { return 1; }
        public Set<Path> reservedPaths() { return Set.of(); }
        public Sleeper sleeper() { return d -> {}; }
        public Clock clock() { return Clock.systemUTC(); }
        public String lookup(String reference) { return secrets.get(reference); }
    };

    @BeforeEach
    void workspace() throws Exception {
        Files.createDirectories(dir.resolve("work"));
    }

    GitKind kind() {
        return new GitKind(s -> fake);
    }

    static Map<String, String> options(String... pairs) {
        Map<String, String> o = new HashMap<>(Map.of("workspace", "Code", "workspace.root", "work", "isolation", "container", "image", "alpine/git:2.47.2"));
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) o.remove(pairs[i]);
            else o.put(pairs[i], pairs[i + 1]);
        }
        return o;
    }

    static final Map<String, String> PUSH = options("push", "true", "push_branches", "agent/*", "network", "allow", "hosts", "github.com");

    JsonNode call(Map<String, String> o, Object... args) throws Exception {
        Map<String, Object> a = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out = kind().create("Repo", o, dir, context).execute(a);
        return out.startsWith("Error:") || out.startsWith("(") ? JSON.getNodeFactory().textNode(out) : JSON.readTree(out);
    }

    List<String> gitArgs(int i) {
        SandboxRequest r = fake.requests.get(i);
        assertThat(r.program()).isEqualTo("git");
        List<String> a = r.args();
        return a.subList(a.indexOf("--no-pager") + 1, a.size());
    }

    @Test
    void statusRunsGitHardenedThroughTheSandbox() throws Exception {
        JsonNode r = call(options(), "action", "status");
        assertThat(r.get("action").asText()).isEqualTo("status");
        assertThat(r.get("exit").asInt()).isZero();
        assertThat(r.get("isolation").asText()).isEqualTo("container");
        assertThat(fake.requests.get(0).args()).startsWith("-c", "core.hooksPath=/dev/null");
        assertThat(gitArgs(0)).containsExactly("status", "--porcelain=v1", "--branch");
        assertThat(call(options(), "action", "diff", "staged", true, "path", "src").isObject()).isTrue();
        assertThat(gitArgs(1)).contains("--cached", "src");
        call(options(), "action", "log", "max", "3");
        assertThat(gitArgs(2)).endsWith("-n", "3");
        call(options(), "action", "branches");
        assertThat(gitArgs(3)).startsWith("branch");
        assertThat(call(options(), "action", "log", "max", "lots").asText()).contains("whole number");
        assertThat(call(options(), "action", "rebase").asText()).contains("action must be one of");
        assertThat(call(options()).get("action").asText()).as("status by default").isEqualTo("status");
    }

    @Test
    void aCommitAddsThenCommitsAsTheDeclaredAuthorAndFailsLoudly() throws Exception {
        call(options("author_name", "Release bot", "author_email", "bot@example.com"), "action", "commit", "message", "Fix it", "paths", List.of("a.txt"));
        assertThat(gitArgs(0)).containsExactly("add", "--", "a.txt");
        assertThat(fake.requests.get(1).args()).contains("user.name=Release bot", "user.email=bot@example.com");
        assertThat(fake.requests.get(1).args()).endsWith("commit", "--no-verify", "--no-gpg-sign", "-m", "Fix it");
        fake = new FakeSandbox(Isolation.CONTAINER).answering(r -> new SandboxResult(1, false, "", "nothing to commit", false, 0, Isolation.CONTAINER, 1));
        step = "main/s2";
        assertThat(call(options(), "action", "commit", "message", "x").asText()).contains("git failed (exit 1)").contains("nothing to commit");
        assertThat(call(options(), "action", "commit", "message", "x", "paths", "a.txt").asText()).contains("paths must be a list");
        step = "main/s3";
        assertThat(call(options(), "action", "branch", "name", "--force").asText()).contains("not a branch name");
    }

    @Test
    void aPushGoesOnlyWhereTheScriptAllowsAndNeverByDefault() throws Exception {
        assertThat(call(options(), "action", "push", "branch", "agent/x").asText()).contains("does not push");
        assertThat(call(PUSH, "action", "push", "branch", "main").asText()).contains("not allowed").contains("agent/*");
        assertThat(call(PUSH, "action", "push", "branch", "agent/deep/x").asText()).as("* stays within a part").contains("not allowed");
        assertThat(fake.requests).isEmpty();
        call(PUSH, "action", "push", "branch", "agent/fix-1");
        assertThat(gitArgs(0)).containsExactly("push", "--no-verify", "origin", "HEAD:refs/heads/agent/fix-1");
        Map<String, String> wide = new HashMap<>(PUSH);
        wide.put("push_branches", "agent/**");
        step = "main/s2";
        call(wide, "action", "push", "branch", "agent/deep/x");
        assertThat(gitArgs(1)).contains("HEAD:refs/heads/agent/deep/x");
    }

    @Test
    void aPushIsDoneOnceAndARestartedRunDoesNotPushAgain() throws Exception {
        call(PUSH, "action", "push", "branch", "agent/a");
        String again = kind().create("Repo", PUSH, dir, context).execute(Map.of("action", "push", "branch", "agent/a"));
        assertThat(again).startsWith("(already done");
        assertThat(fake.requests).hasSize(1);
        // a read in the same step on a rebuilt tool still runs: git's own commands are not journaled twice
        call(PUSH, "action", "status");
        call(PUSH, "action", "status");
        assertThat(fake.requests).hasSize(3);
    }

    @Test
    void aTokenReachesGitByNameThroughItsEnvironment() throws Exception {
        secrets.put("secret.GH", "ghp_test_canary_0000000000");
        Map<String, String> o = new HashMap<>(PUSH);
        o.put("env.GIT_TOKEN", "secret.GH");
        call(o, "action", "push", "branch", "agent/a");
        SandboxRequest r = fake.requests.get(0);
        assertThat(r.env()).containsEntry("GIT_TOKEN", "ghp_test_canary_0000000000");
        assertThat(r.args()).anyMatch(a -> a.contains("$GIT_TOKEN")).noneMatch(a -> a.contains("canary"));
        assertThat(String.join("\n", traced)).doesNotContain("canary");
    }

    @Test
    void theOptionsAreCheckedHereAndByTheSandbox() {
        GitKind k = kind();
        assertThat(k.check(options(), dir)).isNull();
        assertThat(k.check(options("isolation", null), dir)).contains("isolation");
        assertThat(k.check(options("push", "true"), dir)).contains("push_branches: is required");
        assertThat(k.check(options("push_branches", "x"), dir)).contains("go with push: true");
        assertThat(k.check(options("push", "true", "push_branches", "agent/*"), dir)).contains("a push needs the remote's host");
        assertThat(k.check(options("push", "true", "push_branches", "agent/*", "network", "open", "remote", "-x"), dir)).contains("remote:");
        assertThat(k.check(options("author_email", "nope"), dir)).contains("author_email");
        assertThat(k.check(PUSH, dir)).isNull();
        assertThat(new GitKind().check(options("isolation", "process", "image", null), dir)).as("the sandbox's own rule: process needs network: open").contains("network");
    }

    @Test
    void theAuditSeesWhatItCanChangeAndWhereItPushes() {
        GitKind k = new GitKind();
        ToolProfile read = k.profile(options());
        assertThat(read.privateData()).isTrue();
        assertThat(read.outward()).isFalse();
        assertThat(read.isolation()).isEqualTo(Isolation.CONTAINER);
        assertThat(read.journaled()).isTrue();
        assertThat(read.note()).contains("no push");
        ToolProfile push = k.profile(PUSH);
        assertThat(push.outward() && push.untrusted()).isTrue();
        assertThat(push.note()).contains("pushes to origin").contains("agent/*");
        assertThat(k.agentProblem(options(), "Repo", "Coder", false)).isNull();
        assertThat(k.agentProblem(PUSH, "Repo", "Coder", false)).contains("approve");
        assertThat(k.agentProblem(PUSH, "Repo", "Coder", true)).isNull();
        Map<String, String> unattended = new HashMap<>(PUSH);
        unattended.put("unattended", "true");
        assertThat(k.agentProblem(unattended, "Repo", "Coder", false)).isNull();
        assertThat(k.uncheckedResult(Map.of())).containsExactlyInAnyOrder("stdout", "stderr");
        assertThat(k.name()).isEqualTo("git");
        assertThat(k.buildsAtRunStart()).isTrue();
        assertThat(k.references()).containsEntry("workspace", "workspace");
        assertThat(k.prefixes()).contains("env.");
        assertThat(k.resolvedPerCall()).contains("env.");
        assertThat(k.required()).containsExactlyInAnyOrder("workspace", "isolation");
        assertThat(GitKind.sandboxOptions(PUSH)).containsEntry("allow", "git").doesNotContainKeys("push", "push_branches");
    }

    @Test
    void theToolSaysWhatItDoes() throws Exception {
        Tool t = kind().create("Repo", PUSH, dir, context);
        assertThat(t.getDescription()).contains("push (branch, one of agent/*");
        assertThat(kind().create("Repo", options(), dir, context).getDescription()).doesNotContain("push (");
    }

    static Function<SandboxRequest, SandboxResult> exit(int code) {
        return r -> new SandboxResult(code, false, "", "", false, 0, Isolation.CONTAINER, 1);
    }
}
