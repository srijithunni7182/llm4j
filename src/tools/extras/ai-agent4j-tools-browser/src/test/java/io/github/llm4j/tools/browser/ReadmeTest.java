package io.github.llm4j.tools.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The README's declaration is one the kind accepts, and its tables name every option and action. */
class ReadmeTest {

    @Test
    void theScriptsDeclarationIsAcceptedAndEveryOptionAndActionIsDocumented() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        Matcher m = Pattern.compile("tool Web \\{ use: browser(.*?)\\}").matcher(readme);
        assertThat(m.find()).isTrue();
        Map<String, String> options = new LinkedHashMap<>();
        Matcher pair = Pattern.compile("(\\w+): (\"[^\"]*\"|\\S+)").matcher(m.group(1));
        while (pair.find()) options.put(pair.group(1), pair.group(2).replace("\"", ""));
        BrowserKind kind = new BrowserKind();
        assertThat(kind.check(options, Path.of("."))).isNull();
        for (String o : kind.optional()) assertThat(readme).contains("| `" + o + "` |");
        for (String a : List.of("open", "text", "screenshot", "click", "fill", "wait_for", "console", "failed_requests"))
            assertThat(readme).contains("| `" + a + "` |");
    }
}
