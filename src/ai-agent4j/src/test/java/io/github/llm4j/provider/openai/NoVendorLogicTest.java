package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * R2: a vendor difference is data in a dialect or in {@link ModelTraits}; the code that talks to
 * every vendor names none.
 */
class NoVendorLogicTest {

    private static final Path DIR = Path.of("src/main/java/io/github/llm4j/provider/openai");
    private static final Pattern VENDOR =
            Pattern.compile(
                    "(?i)openai|xai|x\\.ai|kimi|moonshot|qwen|dashscope|perplexity|grok|gpt|anthropic|gemini");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void noStringLiteralInTheSharedCodeNamesAVendor() throws Exception {
        for (String file :
                List.of(
                        "OpenAiCompatibleProvider.java",
                        "OpenAiResponsesProvider.java",
                        "ChatRequestWriter.java",
                        "ChatResponseReader.java",
                        "ResponsesRequestWriter.java",
                        "ResponsesReader.java")) {
            String source =
                    Files.readString(DIR.resolve(file))
                            .replaceAll("(?s)/\\*.*?\\*/", "")
                            .replaceAll("(?m)//.*$", "");
            Matcher m = STRING.matcher(source);
            while (m.find()) {
                assertThat(VENDOR.matcher(m.group(1)).find())
                        .as(file + " has the string \"" + m.group(1) + "\"")
                        .isFalse();
            }
        }
    }

    @Test
    void theVendorsLiveInTheDialectsTheTraitsAndTheRegistryOnly() throws Exception {
        for (String file :
                List.of(
                        "Dialect.java",
                        "ResponsesDialect.java",
                        "ModelTraits.java",
                        "Vendor.java")) {
            assertThat(Files.readString(DIR.resolve(file))).as(file).containsPattern(VENDOR);
        }
    }
}
