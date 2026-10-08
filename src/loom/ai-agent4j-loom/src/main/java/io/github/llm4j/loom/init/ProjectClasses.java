package io.github.llm4j.loom.init;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets {@code weave} see the classes of the project it is working on: a task or a tool written in Java is not in the weave jar, so without this
 * {@code weave check} would say the task is unknown. The classes come from {@code --classes} (folders or jars), or, when none is given, from
 * {@code target/classes} of the project that holds the script (what {@code mvn compile} writes).
 */
public final class ProjectClasses {

    private ProjectClasses() {}

    /** The folders and jars that are now on the class path, empty when there were none. Throws {@link IllegalArgumentException} for a given path that is not there. */
    public static List<Path> use(Path script, List<File> given) {
        List<Path> entries = new ArrayList<>();
        if (given != null) {
            for (File f : given) {
                if (!f.exists()) throw new IllegalArgumentException("--classes " + f + " does not exist (build your project first: mvn compile)");
                entries.add(f.toPath().toAbsolutePath().normalize());
            }
        }
        if (entries.isEmpty()) {
            Path built = ProjectLayout.root(script).resolve("target").resolve("classes");
            if (Files.isDirectory(built)) entries.add(built);
        }
        if (entries.isEmpty()) return List.of();
        List<URL> urls = new ArrayList<>();
        for (Path p : entries) {
            try {
                urls.add(p.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(p + " is not a usable class path entry");
            }
        }
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        if (parent == null) parent = ProjectClasses.class.getClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(urls.toArray(URL[]::new), parent));
        return entries;
    }

    /** The loader that sees the project's classes (the context loader, else this library's), for code that loads a class by name. */
    public static ClassLoader loader() {
        ClassLoader l = Thread.currentThread().getContextClassLoader();
        return l != null ? l : ProjectClasses.class.getClassLoader();
    }
}
