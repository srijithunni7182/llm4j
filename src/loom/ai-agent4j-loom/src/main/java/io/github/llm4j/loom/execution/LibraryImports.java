package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** The standard libraries that ship with Loom, written in Loom: {@code import "loom/autonomy"}. */
public final class LibraryImports {

    private static final Pattern NAME = Pattern.compile("loom/([a-z][a-z0-9_-]*)");

    private LibraryImports() { }

    /** True for an import written as a library name, with no file extension: {@code "loom/autonomy"}. */
    public static boolean isLibrary(String importPath) {
        return importPath != null && NAME.matcher(importPath).matches();
    }

    /** The library parsed, or an {@link IllegalArgumentException} naming the libraries there are. */
    public static LoomScript load(String importPath) {
        var m = NAME.matcher(importPath);
        if (!m.matches()) throw new IllegalArgumentException("not a library import: " + importPath);
        String resource = "/loom-lib/" + m.group(1) + ".loom";
        try (InputStream in = LibraryImports.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException("There is no standard library called " + importPath + " (available: loom/autonomy)");
            return new LoomParser(new Lexer(new String(in.readAllBytes(), StandardCharsets.UTF_8)).tokenize()).parseScript();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the library " + importPath, e);
        }
    }
}
