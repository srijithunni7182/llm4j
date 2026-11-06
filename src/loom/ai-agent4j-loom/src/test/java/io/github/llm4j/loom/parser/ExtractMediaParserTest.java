package io.github.llm4j.loom.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.ExtractStmt;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.model.MediaKind;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R16 for {@code extract}: a file is one more input of {@code from},
 * marked by its kind word, alone or labelled beside text inputs. (The owner chose this shape over
 * a trailing {@code attach} on 2026-11-06.)
 */
class ExtractMediaParserTest {

    private static final String EXTRACTOR =
            """
            extractor Receipt {
                model: "gemini-2.5-flash"
                instructions: "Read the receipt."
                fields { vendor: string  total: number }
            }
            """;

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static ExtractStmt extract(String step) {
        LoomScript script =
                parse(EXTRACTOR + "workflow Main(receipt, memo, image) {\n" + step + "\n}\n");
        return (ExtractStmt) script.getWorkflows().get(0).getStatements().get(0);
    }

    @Test
    void aFileAloneIsTheWholeInput() {
        ExtractStmt stmt = extract("extract Receipt from image \"{receipt}\" -> fields");

        assertThat(stmt.getInputs()).isEmpty();
        assertThat(stmt.getMediaInputs()).hasSize(1);
        assertThat(stmt.getMediaInputs().get(0).label()).isEmpty();
        assertThat(stmt.getMediaInputs().get(0).item().kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(stmt.getMediaInputs().get(0).item().path()).isEqualTo("{receipt}");
        assertThat(stmt.getVariableName()).isEqualTo("fields");
    }

    @Test
    void textAndFilesAreLabelledInputsOfTheSameFrom() {
        ExtractStmt stmt =
                extract(
                        "extract Receipt from (memo = \"Read this receipt\", photo = image \"{receipt}\") -> fields retry 1");

        assertThat(stmt.getInputs()).hasSize(1);
        assertThat(stmt.getInputs().get(0).name()).isEqualTo("memo");
        assertThat(stmt.getInputs().get(0).kind()).isEqualTo(RunStmt.Kind.STRING);
        assertThat(stmt.getMediaInputs()).hasSize(1);
        assertThat(stmt.getMediaInputs().get(0).label()).isEqualTo("photo");
        assertThat(stmt.getRetryCount()).isEqualTo(1);
    }

    @Test
    void severalFilesOfAnyKind() {
        ExtractStmt stmt =
                extract(
                        "extract Receipt from (front = image \"a.png\", call = audio \"b.wav\","
                                + " clip = video \"c.mp4\", terms = document \"d.pdf\") -> fields");

        assertThat(stmt.getMediaInputs())
                .extracting(m -> m.item().kind())
                .containsExactly(
                        MediaKind.IMAGE, MediaKind.AUDIO, MediaKind.VIDEO, MediaKind.DOCUMENT);
        assertThat(stmt.getMediaInputs())
                .extracting(io.github.llm4j.loom.ast.ExtractMedia::label)
                .containsExactly("front", "call", "clip", "terms");
    }

    @Test
    void aVariableCalledImageIsStillAVariable() {
        // the kind word is a media input only when a quoted file follows it
        ExtractStmt stmt = extract("extract Receipt from image -> fields");

        assertThat(stmt.getMediaInputs()).isEmpty();
        assertThat(stmt.getInputs()).hasSize(1);
        assertThat(stmt.getInputs().get(0).kind()).isEqualTo(RunStmt.Kind.REFERENCE);
        assertThat(stmt.getInputs().get(0).text()).isEqualTo("image");
    }

    @Test
    void aTextOnlyExtractIsUnchanged() {
        ExtractStmt stmt = extract("extract Receipt from memo -> fields");

        assertThat(stmt.getMediaInputs()).isEmpty();
        assertThat(stmt.getInputs()).hasSize(1);
    }

    @Test
    void aLabelledVariableNamedLikeAKindIsStillAReference() {
        ExtractStmt stmt = extract("extract Receipt from (memo = memo, photo = image) -> fields");

        assertThat(stmt.getMediaInputs()).isEmpty();
        assertThat(stmt.getInputs()).hasSize(2);
    }

    @Test
    void theArrowMessageStillExplainsAMediaOnlyInput() {
        assertThatThrownBy(() -> extract("extract Receipt from image \"{receipt}\""))
                .hasMessageContaining("->")
                .hasMessageContaining("extract Receipt from");
    }

    // ── the extractor's own setting for personal data ──────────────────────────────────────

    @Test
    void anExtractorCanAcceptUnscannedMedia() {
        LoomScript script =
                parse(
                        """
                        extractor Receipt {
                            model: "gemini-2.5-flash"
                            fields { total: number }
                            pii: mask
                            media: unscanned
                        }
                        """);
        ExtractorDef def = script.findExtractor("Receipt");

        assertThat(def.isUnscannedMedia()).isTrue();
    }

    @Test
    void anExtractorScansNothingUnlessTheScriptSaysSo() {
        ExtractorDef def = parse(EXTRACTOR).findExtractor("Receipt");

        assertThat(def.isUnscannedMedia()).isFalse();
    }

    @Test
    void theMediaSettingTakesOnlyTheWordUnscanned() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        extractor Receipt {
                                            model: "gemini-2.5-flash"
                                            fields { total: number }
                                            media: scanned
                                        }
                                        """))
                .hasMessageContaining("media: unscanned");
    }

    @Test
    void theMediaSettingIsListedAmongThoseAnExtractorCanHave() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        extractor Receipt {
                                            model: "gemini-2.5-flash"
                                            fields { total: number }
                                            colour: red
                                        }
                                        """))
                .hasMessageContaining("media");
    }
}
