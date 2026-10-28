package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The front door of the repository: every picture and link in the README and the developer guide is
 * a file that exists.
 */
class DeveloperGuideLinksTest {

    private static final Path ROOT = Path.of("../../..");

    private static void everyLocalTargetExists(Path file) throws Exception {
        String text = Files.readString(file);
        Pattern p = Pattern.compile("(?:\\]\\(|src=\"|href=\")([^)\"#\\s]+)");
        Matcher m = p.matcher(text);
        int seen = 0;
        while (m.find()) {
            String target = m.group(1);
            if (target.startsWith("http") || target.startsWith("mailto:")) continue;
            seen++;
            assertThat(file.getParent().resolve(target).normalize())
                    .as(file.getFileName() + " links " + target)
                    .exists();
        }
        assertThat(seen).isGreaterThan(10);
    }

    @Test
    void theReadmeLinksOnlyToThingsThatExist() throws Exception {
        everyLocalTargetExists(ROOT.resolve("README.md"));
    }

    @Test
    void theDeveloperGuideLinksOnlyToThingsThatExist() throws Exception {
        everyLocalTargetExists(ROOT.resolve("DEVELOPER_GUIDE.md"));
    }

    @Test
    void theReadmeSendsPeopleToTheDeveloperGuide() throws Exception {
        assertThat(Files.readString(ROOT.resolve("README.md")))
                .contains("DEVELOPER_GUIDE.md", "docs/COMMAND_REFERENCE.md");
    }
}
