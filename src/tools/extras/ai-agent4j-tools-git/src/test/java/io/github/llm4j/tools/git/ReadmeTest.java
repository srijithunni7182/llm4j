package io.github.llm4j.tools.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.tools.FakeSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The README's declaration is one the kind accepts, and its tables name every option and action. */
class ReadmeTest {

    @TempDir Path dir;

    @Test
    void theScriptsDeclarationIsAccepted() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        Matcher m = Pattern.compile("tool Repo \\{ use: git(.*?)\\}").matcher(readme);
        assertThat(m.find()).isTrue();
        Map<String, String> options = new LinkedHashMap<>();
        Matcher pair = Pattern.compile("([\\w.]+): (\"[^\"]*\"|\\S+)").matcher(m.group(1));
        while (pair.find()) options.put(pair.group(1), pair.group(2).replace("\"", ""));
        options.put("workspace.root", "project");
        Files.createDirectories(dir.resolve("project"));
        assertThat(options).containsKeys("push", "push_branches", "env.GIT_TOKEN");
        assertThat(new GitKind(s -> new FakeSandbox(Isolation.CONTAINER)).check(options, dir)).isNull();
        for (String own : GitKind.OWN) assertThat(readme).contains("`" + own + "`");
        for (String action : GitKind.actions()) assertThat(readme).contains("| `" + action + "`");
    }
}
