package io.github.llm4j.tools.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.agent.tool.EffectContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * With the real {@code git} (process isolation, the weakest level): a hook the repository holds
 * never runs, and a commit lands. Needs git on the PATH.
 */
@Tag("integration")
class RealGitTest {

    @TempDir Path dir;

    static int git(Path repo, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(30, TimeUnit.SECONDS);
        return p.exitValue();
    }

    @Test
    void aHookInTheRepositoryNeverRunsAndTheCommitLands() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("work"));
        try {
            assumeTrue(git(repo, "init", "-q") == 0, "git is not installed here");
        } catch (java.io.IOException none) {
            assumeTrue(false, "git is not installed here");
        }
        // post-commit, which --no-verify does not skip: only core.hooksPath keeps it from running
        Path hook = repo.resolve(".git/hooks/post-commit");
        Files.writeString(hook, "#!/bin/sh\ntouch \"" + dir.resolve("hook-ran") + "\"\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        var tool = new GitKind().create("Repo", Map.of(
                "workspace", "Code", "workspace.root", "work", "isolation", "process", "network", "open"), dir, EffectContext.noop());
        String out = tool.execute(Map.of("action", "commit", "message", "First"));
        assertThat(out).doesNotStartWith("Error:");
        assertThat(dir.resolve("hook-ran")).as("the hook did not run").doesNotExist();
        assertThat(tool.execute(Map.of("action", "log"))).contains("First").contains("llm4j agent");
        assertThat(tool.execute(Map.of("action", "status"))).contains("\"exit\":0");
    }
}
