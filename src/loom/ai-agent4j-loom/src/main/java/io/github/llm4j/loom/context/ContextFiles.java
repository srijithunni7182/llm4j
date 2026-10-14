package io.github.llm4j.loom.context;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The context files the command line named, for the executors it builds: {@code --context-windows} and {@code --loct}, plus the files that sit beside the
 * script ({@code <script>.windows}, {@code <script>.loct}) and the user's windows file. A process holds one set, as {@code --classes} does.
 */
public final class ContextFiles {

    private static volatile ContextFiles current = new ContextFiles(null, List.of(), List.of());

    private final Path script;
    private final List<Path> windows;
    private final List<Path> loct;

    private ContextFiles(Path script, List<Path> windows, List<Path> loct) {
        this.script = script;
        this.windows = List.copyOf(windows);
        this.loct = List.copyOf(loct);
    }

    /** Remembers the files for this process. Throws {@link IllegalArgumentException} for a named file that does not exist. */
    public static void use(Path script, List<File> windowsFiles, List<File> loctFiles) {
        List<Path> w = new ArrayList<>();
        List<Path> l = new ArrayList<>();
        if (windowsFiles != null) for (File f : windowsFiles) w.add(exists("--context-windows", f));
        if (loctFiles != null) for (File f : loctFiles) l.add(exists("--loct", f));
        current = new ContextFiles(script == null ? null : script.toAbsolutePath().normalize(), w, l);
    }

    private static Path exists(String option, File f) {
        if (!f.isFile()) throw new IllegalArgumentException(option + " " + f + " does not exist");
        return f.toPath().toAbsolutePath().normalize();
    }

    /** Looks for the files beside this script, unless {@link #use} already named a script. For commands that were not given the options (resume, tick). */
    public static void beside(Path script) {
        ContextFiles c = current;
        if (c.script == null && script != null) current = new ContextFiles(script.toAbsolutePath().normalize(), c.windows, c.loct);
    }

    public static ContextFiles current() {
        return current;
    }

    /** Forgets everything (for tests). */
    public static void clear() {
        current = new ContextFiles(null, List.of(), List.of());
    }

    /** The windows, layered: command line, beside the script, the user's file, the catalogue. */
    public WindowLayers windowLayers() {
        return WindowLayers.load(windows, script == null ? null : sibling(script, "windows"), userFile());
    }

    /** The {@code .loct} files, lowest priority first: beside the script, then those named on the command line. */
    public List<Path> loctFiles() {
        List<Path> all = new ArrayList<>();
        if (script != null) {
            Path beside = sibling(script, "loct");
            if (java.nio.file.Files.isRegularFile(beside)) all.add(beside);
        }
        all.addAll(loct);
        return all;
    }

    /** {@code flow.loom} to {@code flow.windows} or {@code flow.loct}, in the same folder. */
    public static Path sibling(Path script, String extension) {
        String name = script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return script.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + "." + extension);
    }

    private static Path userFile() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".llm4j", "context-windows");
    }
}
