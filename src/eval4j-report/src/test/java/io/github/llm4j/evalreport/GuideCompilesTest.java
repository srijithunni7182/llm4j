package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Java block in the guide that follows {@code <!-- compiles-with-report -->} is a complete source file that uses the evaluation classes, so it
 * is compiled here, where they are on the class path (the Loom module cannot see them).
 */
class GuideCompilesTest {

    private static final Path GUIDE = Path.of("..", "..", "docs", "guide");
    private static final Pattern BLOCK = Pattern.compile("<!-- compiles-with-report -->\\s*```java\\n(.*?)```", Pattern.DOTALL);

    @TempDir Path dir;

    @Test
    void everyMarkedJavaBlockInTheGuideCompiles() throws Exception {
        List<String> paths = new ArrayList<>();
        try (var files = Files.list(GUIDE)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                Matcher m = BLOCK.matcher(Files.readString(f));
                while (m.find()) {
                    String source = m.group(1);
                    String pkg = find(source, "package (.+?);");
                    String type = find(source, "(?:public )?(?:final )?(?:class|interface|record) (\\w+)");
                    Path file = dir.resolve("src").resolve(pkg.replace('.', '/')).resolve(type + ".java");
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, source);
                    paths.add(file.toString());
                }
            }
        }
        assertThat(paths).as("the guide has at least the trajectory test").isNotEmpty();
        Files.createDirectories(dir.resolve("out"));
        List<String> args = new ArrayList<>(List.of("-proc:none", "-cp", System.getProperty("java.class.path"), "-d", dir.resolve("out").toString()));
        args.addAll(paths);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).as("the guide's example code compiles").isZero();
    }

    private static String find(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        assertThat(m.find()).as(regex).isTrue();
        return m.group(1);
    }
}
