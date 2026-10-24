package io.github.llm4j.loom.review.pack;

import io.github.llm4j.loom.review.engine.ReviewRule;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Finds the Java rules ({@link ReviewRule}) in jars that the user named. Nothing is loaded unless a
 * jar is named, so a script's folder cannot make weave run code.
 */
public final class JavaRules {

    private JavaRules() {}

    /** The rules of every jar, in the order the jars were named. */
    public static List<ReviewRule> fromJars(List<Path> jars) {
        List<ReviewRule> rules = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) {
                problems.add(jar + " does not exist");
                continue;
            }
            try {
                URLClassLoader loader =
                        new URLClassLoader(
                                new URL[] {jar.toUri().toURL()}, ReviewRule.class.getClassLoader());
                List<ReviewRule> found = load(loader);
                if (found.isEmpty())
                    problems.add(
                            jar
                                    + " provides no rules: list the class in META-INF/services/"
                                    + ReviewRule.class.getName());
                rules.addAll(found);
            } catch (IOException | java.util.ServiceConfigurationError e) {
                problems.add(jar + " could not be loaded: " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) throw new PackException("--rules-jar", problems);
        return rules;
    }

    /** The rules a class loader offers. */
    public static List<ReviewRule> load(ClassLoader loader) {
        List<ReviewRule> out = new ArrayList<>();
        for (ReviewRule r : ServiceLoader.load(ReviewRule.class, loader)) out.add(r);
        return out;
    }
}
