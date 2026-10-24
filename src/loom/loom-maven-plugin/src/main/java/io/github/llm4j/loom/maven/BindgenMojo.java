package io.github.llm4j.loom.maven;

import io.github.llm4j.loom.bind.Bindgen;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Typed Java bindings for Loom scripts, in {@code generate-sources}. By default it writes into
 * {@code target/generated-sources/loom} and adds that directory as a source root. With {@code
 * failOnDrift} it writes nothing and fails the build when the files already in {@code
 * outputDirectory} (checked in) differ from what the scripts give: the guard against a script
 * changed without regenerating.
 */
@Mojo(name = "bindgen", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class BindgenMojo extends AbstractMojo {

    /** One script and the package its bindings go in. */
    public static class Script {
        /** The script file. */
        public File file;

        /** The Java package of the bindings. */
        public String pkg;

        /** The facade's name; by default from the script's name. */
        public String facade;
    }

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter(required = true)
    List<Script> scripts = new ArrayList<>();

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/loom")
    File outputDirectory;

    /** none (default), jspecify or optional. */
    @Parameter(defaultValue = "none")
    String nullability = "none";

    /** Check, do not write: fail when the files in outputDirectory are out of date. */
    @Parameter(defaultValue = "false")
    boolean failOnDrift;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (scripts == null || scripts.isEmpty())
            throw new MojoExecutionException("loom:bindgen needs at least one <script>.");
        Bindgen.Nullability n;
        try {
            n = Bindgen.Nullability.valueOf(nullability.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("nullability is none, jspecify or optional.");
        }
        List<String> stale = new ArrayList<>();
        for (Script s : scripts) {
            if (s.file == null || s.pkg == null)
                throw new MojoExecutionException("a <script> needs <file> and <pkg>.");
            if (!s.file.isFile())
                throw new MojoExecutionException("the script " + s.file + " is not there.");
            Bindgen.Generated g;
            try {
                g = Bindgen.generate(s.file.toPath(), s.pkg, s.facade, n);
            } catch (Exception e) {
                throw new MojoExecutionException(
                        s.file + ": " + (e.getMessage() == null ? e : e.getMessage()), e);
            }
            g.notes().forEach(note -> getLog().info(note));
            Path root = outputDirectory.toPath();
            try {
                for (Map.Entry<String, String> f : g.files().entrySet()) {
                    Path target = root.resolve(f.getKey());
                    String disk =
                            Files.isRegularFile(target)
                                    ? Files.readString(target, StandardCharsets.UTF_8)
                                    : null;
                    if (f.getValue().equals(disk)) continue;
                    if (failOnDrift) stale.add(root.relativize(target).toString());
                    else {
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("cannot write the bindings: " + e.getMessage(), e);
            }
        }
        if (!stale.isEmpty())
            throw new MojoFailureException(
                    "the bindings are out of date: "
                            + String.join(", ", stale)
                            + ". Run `mvn loom:bindgen` without failOnDrift (or weave bindgen) and commit the result.");
        if (!failOnDrift && project != null)
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
    }
}
