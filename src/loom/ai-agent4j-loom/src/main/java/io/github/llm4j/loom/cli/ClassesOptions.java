package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.init.ProjectClasses;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Option;

/** {@code --classes}: where the classes of your own tasks and tools are. By default {@code target/classes} of the project holding the script, when it is built. */
final class ClassesOptions {

    @Option(names = "--classes", paramLabel = "<dir|jar>",
            description = "Folder or jar with the classes of your own tasks and tools, repeatable (weave only sees what is on its class path). Default: target/classes of the project, when it has been built (mvn compile).")
    List<File> classes;

    /** Puts the classes on the class path; false (after saying why) when one given is missing. */
    boolean apply(Path script, WeaveEnv env) {
        try {
            ProjectClasses.use(script, classes);
            return true;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return false;
        }
    }
}
