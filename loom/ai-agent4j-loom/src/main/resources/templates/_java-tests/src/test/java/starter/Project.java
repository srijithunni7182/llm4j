package starter;

import java.nio.file.Files;
import java.nio.file.Path;

/** Where this project keeps its workflow and its golden dataset: the Maven layout, or the older flat folder. */
final class Project {

    private Project() {}

    static Path script() {
        Path maven = Path.of("src", "main", "resources", "main.loom").toAbsolutePath();
        return Files.isRegularFile(maven) ? maven : Path.of("main.loom").toAbsolutePath();
    }

    static Path dataset() {
        Path maven = Path.of("src", "test", "resources", "eval", "golden").toAbsolutePath();
        return Files.isDirectory(maven) ? maven : Path.of("eval", "golden").toAbsolutePath();
    }
}
