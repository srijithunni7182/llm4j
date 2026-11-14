package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a tool returns for an answer (phase 36a, D5, G72), asked of the renderer directly. */
class AnswersTest {

    @TempDir Path dir;

    private static HttpSupport.Reply reply(int status, String type, byte[] body, boolean truncated) {
        return new HttpSupport.Reply(status, status == 200 ? "OK" : "Not OK", type, body, truncated, Headers.of());
    }

    private static HttpSupport.Reply text(String body, String type) {
        return reply(200, type, body.getBytes(StandardCharsets.UTF_8), false);
    }

    private Answers.Settings capped(long max) {
        return new Answers.Settings(max, null, max);
    }

    private Answers.Settings saving(long max, long saveMax) {
        return new Answers.Settings(max, dir.resolve("out"), saveMax);
    }

    private long saved() throws IOException {
        Path out = dir.resolve("out");
        if (!Files.exists(out)) return 0;
        try (Stream<Path> files = Files.list(out)) {
            return files.count();
        }
    }

    @Test
    void anEmptyAnswerIsJustItsStatusLine() {
        assertThat(Answers.render("T", reply(204, null, new byte[0], false), capped(100))).isEqualTo("HTTP 204 Not OK");
    }

    @Test
    void aTextAnswerShowsStatusTypeAndBody() {
        assertThat(Answers.render("T", text("hello", "text/plain; charset=utf-8"), capped(100)))
                .isEqualTo("HTTP 200 OK\nContent-Type: text/plain; charset=utf-8\n\nhello");
    }

    @Test
    void aReasonlessStatusLineHasNoTrailingSpace() {
        var bare = new HttpSupport.Reply(200, "", "text/plain", "x".getBytes(), false, Headers.of());

        assertThat(Answers.render("T", bare, capped(100))).startsWith("HTTP 200\n");
    }

    @Test
    void aBodyNamingNoTypeIsTextWhenItsBytesAre() {
        assertThat(Answers.render("T", reply(200, null, "plain words\twith a tab\nand a line".getBytes(), false), capped(100)))
                .contains("plain words");
        assertThat(Answers.render("T", reply(200, "  ", "more".getBytes(), false), capped(100))).contains("more");
    }

    @Test
    void aBodyNamingNoTypeThatIsNotTextIsRefusedAndSaysItIsOfUnknownType() {
        assertThatThrownBy(() -> Answers.render("T", reply(200, null, new byte[] {1, 2, 0, 3}, false), capped(100)))
                .isInstanceOf(ToolRefusal.class)
                .hasMessageContaining("of unknown type");
        assertThatThrownBy(() -> Answers.render("T", reply(200, null, new byte[] {(byte) 0xC3, 0x28}, false), capped(100)))
                .hasMessageContaining("of unknown type");
    }

    @Test
    void aNamedTypeThatIsNotTextIsRefusedWithItsName() {
        assertThatThrownBy(() -> Answers.render("T", reply(200, "image/png", new byte[] {1, 2}, false), capped(100)))
                .hasMessageContaining("image/png")
                .hasMessageContaining("not text");
    }

    @Test
    void theTextTypesAModelCanRead() {
        for (String type : new String[] {"application/json", "application/xml", "application/x-www-form-urlencoded", "application/problem+json", "application/atom+xml", "TEXT/HTML"})
            assertThat(Answers.isTextual(type)).as(type).isTrue();
        for (String type : new String[] {"image/png", "application/octet-stream", "application/pdf", null, ""})
            assertThat(Answers.isTextual(type)).as(String.valueOf(type)).isFalse();
    }

    @Test
    void aTruncatedAnswerWithoutASaveDirSaysSoWithHowMuchWasShown() {
        String out = Answers.render("T", reply(200, "text/plain", "abcde".getBytes(), true), capped(5));

        assertThat(out).endsWith("abcde" + "\n… [cut: first 5 bytes shown, more follows]");
    }

    @Test
    void anAnswerExactlyAtTheCapIsShownWholeAndOneByteOverIsSaved() throws IOException {
        Answers.Settings settings = saving(5, 100);

        String exact = Answers.render("T", text("abcde", "text/plain"), settings);
        assertThat(exact).endsWith("abcde").doesNotContain("saved to");
        assertThat(saved()).isZero();

        String over = Answers.render("T", text("abcdef", "text/plain"), settings);
        assertThat(over).contains("6 bytes").contains("saved to").contains("The start of it:");
        assertThat(saved()).isEqualTo(1);
    }

    @Test
    void aSavedTextAnswerShowsTheStartOfItAndABinaryOneDoesNot() throws IOException {
        Answers.Settings settings = saving(5, 100000);

        String text = Answers.render("My tool", text("x".repeat(5000), "application/json"), settings);
        String binary = Answers.render("My tool", reply(200, "application/pdf", new byte[5000], false), settings);

        assertThat(text).contains("The start of it:").contains("[cut:");
        assertThat(binary).contains("5000 bytes").contains(".pdf").doesNotContain("The start of it");
        assertThat(saved()).isEqualTo(2);
        try (Stream<Path> files = Files.list(dir.resolve("out"))) {
            assertThat(files.map(p -> p.getFileName().toString())).allMatch(n -> n.startsWith("My_tool-"));
        }
    }

    @Test
    void whenTheReadLimitCutTheBodyTheSummarySaysHowMuchWasKept() {
        String out = Answers.render("T", reply(200, "text/plain", "y".repeat(50).getBytes(), true), saving(5, 50));

        assertThat(out).contains("50+ bytes").contains("only the first 50 bytes (save_max_bytes) were kept");
    }

    @Test
    void aTextAnswerWithNoTypeIsSavedWithABinExtensionNameFromItsHash() throws IOException {
        Answers.render("T", reply(200, null, "z".repeat(500).getBytes(), false), saving(5, 1000));

        try (Stream<Path> files = Files.list(dir.resolve("out"))) {
            assertThat(files.map(p -> p.getFileName().toString())).singleElement().satisfies(n -> assertThat(n).matches("T-[0-9a-f]{16}\\.bin"));
        }
    }

    @Test
    void theExtensionFollowsTheContentTypeAndNeverTheServer() throws IOException {
        Answers.Settings settings = saving(1, 1000);
        for (String type : new String[] {"text/csv", "application/xml", "image/jpeg", "image/gif", "TEXT/PLAIN; charset=utf-8"})
            Answers.render("T", text("content for " + type, type), settings);

        try (Stream<Path> files = Files.list(dir.resolve("out"))) {
            assertThat(files.map(p -> p.getFileName().toString().replaceAll(".*\\.", "")).sorted()).containsExactly("csv", "gif", "jpg", "txt", "xml");
        }
    }

    @Test
    void aToolNameWithOddCharactersMakesASafeFileName() throws IOException {
        Answers.render("../../etc/pass wd", text("x".repeat(50), "text/plain"), saving(5, 100));
        Answers.render("", text("y".repeat(50), "text/plain"), saving(5, 100));

        try (Stream<Path> files = Files.list(dir.resolve("out"))) {
            assertThat(files.map(p -> p.getFileName().toString())).allMatch(n -> n.matches("[A-Za-z0-9_-]+-[0-9a-f]{16}\\.txt"));
        }
        assertThat(dir.resolve("etc")).doesNotExist();
    }

    @Test
    void theReadLimitIsTheLargerOfTheShownAndTheSavedWhenThereIsASaveDir() {
        assertThat(capped(10).readLimit()).isEqualTo(10);
        assertThat(saving(10, 1000).readLimit()).isEqualTo(1000);
        assertThat(saving(1000, 10).readLimit()).isEqualTo(1000);
    }
}
