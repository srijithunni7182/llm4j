package io.github.llm4j.loom.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R11 of spec 15: the goal writes bindings, adds the source root, and fails on drift. */
class BindgenMojoTest {

    @TempDir Path dir;

    private static final String SCRIPT =
            "workflow Main(topic) {\n    set answer = \"about {topic}\"\n    return { answer: \"{answer}\" }\n}\n";

    private BindgenMojo mojo(boolean failOnDrift, Path script) {
        BindgenMojo m = new BindgenMojo();
        BindgenMojo.Script s = new BindgenMojo.Script();
        s.file = script.toFile();
        s.pkg = "com.acme";
        m.scripts = List.of(s);
        m.outputDirectory = dir.resolve("gen").toFile();
        m.failOnDrift = failOnDrift;
        m.project = new MavenProject();
        m.project.setBuild(new Build());
        return m;
    }

    @Test
    void writesTheBindingsAndAddsTheDirectoryAsASourceRoot() throws Exception {
        Path script = dir.resolve("flow.loom");
        Files.writeString(script, SCRIPT);
        BindgenMojo m = mojo(false, script);
        m.execute();
        assertThat(dir.resolve("gen/com/acme/FlowWorkflows.java")).exists();
        assertThat(m.project.getCompileSourceRoots()).contains(dir.resolve("gen").toString());
    }

    @Test
    void failOnDriftWritesNothingAndFailsWhenTheFilesAreOutOfDate() throws Exception {
        Path script = dir.resolve("flow.loom");
        Files.writeString(script, SCRIPT);
        mojo(false, script).execute();
        mojo(true, script).execute(); // up to date: passes
        Files.writeString(script, SCRIPT.replace("answer:", "reply:"));
        String before = Files.readString(dir.resolve("gen/com/acme/FlowWorkflows.java"));
        assertThatThrownBy(() -> mojo(true, script).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("out of date")
                .hasMessageContaining("FlowWorkflows.java");
        assertThat(Files.readString(dir.resolve("gen/com/acme/FlowWorkflows.java"))).isEqualTo(before);
    }

    @Test
    void aMissingScriptOrABadOptionIsAnExecutionError() throws Exception {
        assertThatThrownBy(() -> mojo(false, dir.resolve("none.loom")).execute())
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("is not there");
        Path script = dir.resolve("flow.loom");
        Files.writeString(script, SCRIPT);
        BindgenMojo m = mojo(false, script);
        m.nullability = "maybe";
        assertThatThrownBy(m::execute).hasMessageContaining("none, jspecify or optional");
        m = mojo(false, script);
        m.scripts = List.of();
        assertThatThrownBy(m::execute).hasMessageContaining("at least one");
    }
}
