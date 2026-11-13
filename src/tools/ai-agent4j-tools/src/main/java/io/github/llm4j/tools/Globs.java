package io.github.llm4j.tools;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Path globs as a workspace option writes them, matched against a path relative to the root with
 * {@code /} between names. A glob with no {@code /} matches a file's name at any depth ({@code
 * *.md}); one with a {@code /} matches the whole relative path ({@code docs/**}); a leading {@code
 * **}{@code /} also matches at the top ({@code **}{@code /*.md} matches {@code a.md}).
 */
final class Globs {

    private final List<String> patterns;
    private final List<PathMatcher> byName = new ArrayList<>();
    private final List<PathMatcher> byPath = new ArrayList<>();

    Globs(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
        for (String p : patterns) {
            if (!p.contains("/")) byName.add(matcher(p));
            else {
                byPath.add(matcher(p));
                if (p.startsWith("**/")) byPath.add(matcher(p.substring(3)));
            }
        }
    }

    private static PathMatcher matcher(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    List<String> patterns() {
        return patterns;
    }

    boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** Whether {@code relative} (with {@code /} between names) matches any glob. */
    boolean matches(String relative) {
        if (relative.isEmpty()) return false;
        Path path = Path.of(relative);
        Path name = path.getFileName();
        for (PathMatcher m : byName) if (m.matches(name)) return true;
        for (PathMatcher m : byPath) if (m.matches(path)) return true;
        return false;
    }

    /** Whether a directory, and so everything under it, is covered ({@code target/**}). */
    boolean coversDirectory(String relative) {
        return matches(relative) || matches(relative + "/x");
    }
}
