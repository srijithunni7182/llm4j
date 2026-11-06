package io.github.llm4j.loom.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The guide's section on {@code attach} is checked against the parser: every example loads. */
class AttachGuideTest {

    private static List<String> blocks() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"), StandardCharsets.UTF_8);
        int from = guide.indexOf("### Sending pictures, recordings and documents");
        int to = guide.indexOf("\n### Model Routing Policies", from);
        assertThat(from).as("the guide has the attach section").isGreaterThanOrEqualTo(0);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(guide.substring(from, to));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void everyLoomBlockInTheAttachSectionParses() throws Exception {
        List<String> blocks = blocks();

        assertThat(blocks).hasSize(3);
        for (String block : blocks) {
            new LoomParser(new Lexer(block).tokenize()).parseScript();
        }
    }
}
