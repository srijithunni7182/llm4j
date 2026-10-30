package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.init.ProjectClasses;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Option;

/**
 * {@code --classes}: where the classes of your own tasks and tools are. By default {@code
 * target/classes} of the project holding the script, when it is built.
 */
final class ClassesOptions {

    @Option(
            names = "--classes",
            paramLabel = "<dir|jar>",
            description =
                    "Folder or jar with the classes of your own tasks and tools, repeatable (weave only sees what is on its class path). Default: target/classes of the project, when it has been built (mvn compile).")
    List<File> classes;

    @Option(
            names = "--no-auto-classes",
            description =
                    "Do not put the project's target/classes on the class path when --classes is not given.")
    boolean noAuto;

    /** Puts the classes on the class path; false (after saying why) when one given is missing. */
    boolean apply(Path script, WeaveEnv env) {
        try {
            List<Path> used = ProjectClasses.use(script, classes, !noAuto);
            if (!used.isEmpty() && (classes == null || classes.isEmpty()))
                env.err().println("note: using the project's classes from " + used.get(0) + " (--no-auto-classes turns this off)");
            return true;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return false;
        }
    }
}
