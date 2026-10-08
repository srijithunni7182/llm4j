package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** R3.1 and R3.4 of loom-onboarding: a chapter of the guide links only to other chapters or to a web address, so it reads the same in the jar. */
class GuideLinksTest {

    static final Path GUIDE = Path.of("../../../docs/guide");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");

    private static List<Path> chapters() throws Exception {
        try (Stream<Path> s = Files.list(GUIDE)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    @Test
    void everyLinkInTheGuideIsAnotherChapterOrAWebAddress() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path chapter : chapters()) {
            Matcher m = LINK.matcher(Files.readString(chapter));
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("#") || target.startsWith("mailto:")) continue;
                String file = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                if (!file.matches("[A-Za-z0-9._-]+\\.md") || !Files.exists(GUIDE.resolve(file))) offenders.add(chapter.getFileName() + " -> " + target);
            }
        }

        assertThat(offenders).as("links that would not work from inside the jar").isEmpty();
    }

    @Test
    void theWebAddressesForRepositoryFilesPointAtFilesThatExistInTheRepository() throws Exception {
        Pattern repo = Pattern.compile("https://github\\.com/srijithunni7182/llm4j/(?:blob|tree)/main/([^)#\\s]+)");
        List<String> missing = new ArrayList<>();
        int seen = 0;
        for (Path chapter : chapters()) {
            Matcher m = repo.matcher(Files.readString(chapter));
            while (m.find()) {
                seen++;
                if (!Files.exists(Path.of("../../..").resolve(m.group(1)))) missing.add(chapter.getFileName() + " -> " + m.group(1));
            }
        }

        assertThat(seen).isGreaterThan(10);
        assertThat(missing).isEmpty();
    }

    @Test
    void theReadmeSaysToStartFromATemplateAndThatHexamindIsACaseStudy() throws Exception {
        String readme = Files.readString(GUIDE.resolve("README.md"));

        assertThat(readme).contains("Start from a template, not from an example").contains("weave init pipeline").contains("case study");
    }
}
