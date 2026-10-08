package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.init.TemplateWriter;
import io.github.llm4j.loom.init.Templates;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave init}: a small, complete, working project to start from: a script, its prompts as files, a golden dataset and a README. No Java.
 * Nothing is overwritten: if any file is already there, nothing is written and the files in the way are named.
 */
@Command(name = "init", description = "Creates a starter project: a Maven project with the script and its prompts in src/main/resources, the golden dataset and eval4j JUnit tests under src/test, and a README. Run 'weave init --list' to see the templates.")
final class InitCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<template>", description = "pipeline, approval, classifier or empty.")
    String template;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<dir>", description = "Where to create it (default: a folder named after the template, here).")
    File dir;

    @Option(names = "--list", description = "List the templates.")
    boolean list;

    @Option(names = "--flat", description = "Create the older flat folder (main.loom, prompts/, eval/golden beside each other, no pom.xml) instead of a Maven project. Add --with-java-tests to put the Java tests in it.")
    boolean flat;

    @Option(names = "--with-java-tests", description = "With --flat: also add the pom.xml and the JUnit tests. (A Maven project always has them.) They run every golden scenario as its own JUnit test on a model that costs nothing; they do not call a real model.")
    boolean withJavaTests;

    @Option(names = "--name", paramLabel = "<name>", description = "The project's name, written into its files (default: the folder's name).")
    String name;

    @Override
    public Integer call() {
        return init(this, WeaveEnv.system());
    }

    static int init(InitCommand c, WeaveEnv env) {
        if (c.list || c.template == null) {
            env.out().println("Templates for weave init <template> [dir]:");
            for (Templates.Template t : Templates.all()) env.out().println("  " + String.format("%-11s", t.name()) + t.description());
            return c.list ? 0 : (c.template == null ? 2 : 0);
        }
        var found = Templates.find(c.template);
        if (found.isEmpty()) {
            List<String> near = PromptCatalog.nearest(c.template, Templates.all().stream().map(Templates.Template::name).toList());
            env.err().println("Error: there is no template named " + c.template + (near.isEmpty() ? "" : "; did you mean " + String.join(", ", near) + "?")
                    + " Run weave init --list to see them.");
            return 2;
        }
        Path target = (c.dir != null ? c.dir.toPath() : Path.of(found.get().name())).toAbsolutePath().normalize();
        String project = c.name != null ? c.name : target.getFileName() == null ? found.get().name() : target.getFileName().toString();
        TemplateWriter.Result result;
        try {
            List<TemplateWriter.Part> parts = new java.util.ArrayList<>(List.of(new TemplateWriter.Part(found.get().name(), found.get().files())));
            if (c.withJavaTests || !c.flat) parts.add(new TemplateWriter.Part(Templates.JAVA_TESTS, Templates.JAVA_TESTS_FILES));
            Map<String, String> where = c.flat
                    ? Map.of("script", "main.loom", "golden", "eval/golden", "prompts", "prompts")
                    : Map.of("script", "src/main/resources/main.loom", "golden", "src/test/resources/eval/golden", "prompts", "src/main/resources/prompts");
            java.util.Map<String, String> variables = new java.util.HashMap<>(where);
            variables.put("name", project);
            variables.put("artifact", artifactId(project));
            result = TemplateWriter.write(parts, target, variables, c.flat ? f -> f : Templates::mavenLayout);
        } catch (java.io.IOException e) {
            env.err().println("Error: could not create the project: " + e.getMessage());
            return 2;
        }
        if (!result.ok()) {
            env.err().println("Error: nothing was written, because these files are already in " + target + ":");
            result.conflicts().forEach(f -> env.err().println("  " + f));
            env.err().println("Choose an empty folder, or move these files first.");
            return 2;
        }
        env.out().println("Created " + found.get().name() + " in " + target + ":");
        result.created().forEach(f -> env.out().println("  " + f));
        env.out().println();
        env.out().println("Next, from that folder (nothing costs money until you run it for real):");
        if (!target.toAbsolutePath().normalize().equals(java.nio.file.Path.of("").toAbsolutePath().normalize())) {
            env.out().println("  cd " + target);
        }
        String script = c.flat ? "main.loom" : "src/main/resources/main.loom";
        env.out().println("  weave check " + script + " --no-env");
        env.out().println("  weave eval " + script + " --mock");
        if (c.withJavaTests || !c.flat) env.out().println("  mvn test                              (the same scenarios as JUnit tests; \"Tests run: 0\" is a failure, and the build says so)");
        if (!c.flat) {
            env.out().println();
            env.out().println("Where things are: the workflow and its prompts are in src/main/resources (they travel with the program), the golden dataset is in");
            env.out().println("src/test/resources/eval/golden, and the JUnit tests that run it are in src/test/java.");
        }
        env.out().println("The README says how to set your model's key and run it.");
        return 0;
    }

    /** A Maven artifactId from a project name: lower-case letters, digits and dashes. */
    static String artifactId(String project) {
        String id = project.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return id.isEmpty() ? "workflow" : id;
    }
}
