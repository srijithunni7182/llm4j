package io.github.llm4j.nirmaan.tasks;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** What the project folder holds, as the factory looks at it: which files are code, which are tests, which are build files. */
final class ProjectFiles {

    static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "package.json", "requirements.txt",
            "pyproject.toml", "go.mod", "Cargo.toml", "Makefile", "tsconfig.json");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of("java", "kt", "py", "js", "jsx", "mjs", "ts", "tsx", "go", "rs", "c", "cc", "cpp", "h", "hpp", "cs",
            "rb", "php", "html", "css", "scss", "vue", "svelte");
    private static final long MAX_FILE_BYTES = 120_000;

    private ProjectFiles() {}

    /** Every regular file under {@code root} that is not build output, sorted, as paths relative to {@code root}. */
    static List<Path> list(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && Sandbox.IGNORED_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !file.getFileName().toString().endsWith(".zip")) files.add(root.relativize(file));
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        return files;
    }

    static boolean isTest(Path relative) {
        String path = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        return path.startsWith("src/test/") || path.contains("/test/") || path.contains("/tests/") || path.startsWith("test/") || path.startsWith("tests/")
                || path.contains("__tests__/") || path.startsWith("e2e-tests/") || path.startsWith("e2e/")
                || name.startsWith("test_") || name.contains("_test.") || name.contains(".test.") || name.contains(".spec.")
                || name.endsWith("test.java") || name.endsWith("tests.java") || name.endsWith("test.kt");
    }

    /** The end to end suite: Drishti's files, which no other step may write. */
    static boolean isE2e(Path relative) {
        String path = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return path.startsWith("e2e-tests/") || path.startsWith("e2e/");
    }

    static boolean isUnitTest(Path relative) {
        return isTest(relative) && !isE2e(relative);
    }

    static boolean isBuildFile(Path relative) {
        return BUILD_FILES.contains(relative.getFileName().toString());
    }

    static boolean isSource(Path relative) {
        String name = relative.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && SOURCE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Text files small enough to show a model; binaries and big generated files are left out. */
    static boolean readable(Path absolute) {
        try {
            if (Files.size(absolute) > MAX_FILE_BYTES) return false;
            byte[] head = Files.readAllBytes(absolute);
            for (int i = 0; i < Math.min(head.length, 2000); i++) if (head[i] == 0) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
