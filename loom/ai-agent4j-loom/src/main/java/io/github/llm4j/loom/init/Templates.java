package io.github.llm4j.loom.init;

import java.util.List;
import java.util.Optional;

/**
 * The starter projects {@code weave init} can create. Each is a folder of files inside the jar ({@code /templates/<name>/…}) with
 * {@code {{name}}} where the project's name goes. A test checks that this list and the files in the jar agree, and that every template
 * passes {@code weave check}, {@code weave audit} and {@code weave eval --mock}, so a template cannot rot.
 */
public final class Templates {

    /** A starter project: what it is for, and the files it holds. */
    public record Template(String name, String description, List<String> files) {}

    private static final List<Template> ALL = List.of(
            new Template("pipeline", "agents in sequence with a bounded review loop (research, write, edit)", List.of(
                    "main.loom", "README.md", "dot-gitignore", "dot-env.example",
                    "prompts/researcher/v1.md", "prompts/writer.md", "prompts/editor.md",
                    "eval/golden/dataset.yaml", "eval/golden/researcher.yaml", "eval/golden/writer.yaml", "eval/golden/editor.yaml", "eval/golden/workflow.yaml")),
            new Template("approval", "a person approves a risky step; a spend cap and a personal-data guard (customer emails)", List.of(
                    "main.loom", "README.md", "dot-gitignore", "dot-env.example",
                    "prompts/triage.md", "prompts/drafter.md",
                    "eval/golden/dataset.yaml", "eval/golden/triage.yaml", "eval/golden/drafter.yaml", "eval/golden/workflow.yaml")),
            new Template("classifier", "one agent that labels things, asks a person when it is unsure, and a golden dataset to measure it", List.of(
                    "main.loom", "README.md", "dot-gitignore", "dot-env.example",
                    "prompts/classifier.md",
                    "eval/golden/dataset.yaml", "eval/golden/classifier.yaml", "eval/golden/workflow.yaml")));

    /** The folder under /templates that holds the Maven test module added by {@code --with-java-tests}; it is not a template of its own. */
    public static final String JAVA_TESTS = "_java-tests";

    /** What {@code --with-java-tests} adds: a pom that runs JUnit 5 and fails when no test ran, a dataset test and a mock wiring test. */
    public static final List<String> JAVA_TESTS_FILES = List.of(
            "pom.xml", "src/test/README.md",
            "src/test/java/starter/Project.java", "src/test/java/starter/GoldenDatasetTest.java", "src/test/java/starter/ScriptWiringTest.java");

    /**
     * Where a starter file goes in a Maven project: the script and its prompts are resources of the program ({@code src/main/resources}), the golden
     * dataset is a resource of its tests ({@code src/test/resources}); the pom, the Java tests, the README and the key files are already where Maven
     * and a person expect them. The templates themselves stay flat: this is the only place the two layouts differ.
     */
    public static String mavenLayout(String file) {
        if (file.equals("main.loom") || file.startsWith("prompts/")) return "src/main/resources/" + file;
        if (file.startsWith("eval/")) return "src/test/resources/" + file;
        return file;
    }

    private Templates() {}

    public static List<Template> all() {
        return ALL;
    }

    public static Optional<Template> find(String name) {
        return ALL.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
    }
}
