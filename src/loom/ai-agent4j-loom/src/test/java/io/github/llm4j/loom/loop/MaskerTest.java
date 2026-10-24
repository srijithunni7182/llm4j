package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R6, R8 of spec 14: nothing personal or secret survives, and what is kept keeps its shape. */
class MaskerTest {

    @Test
    void noSeededValueSurvivesMasking() {
        Masker m = new Masker(Hostile.TERMS);
        String out = m.mask(Hostile.message());
        for (String v : Hostile.mustNotSurvive())
            assertThat(out).as("survivor: " + v).doesNotContain(v);
    }

    @Test
    void anEmailStaysAnEmailAndANumberStaysTheSameLength() {
        Masker m = new Masker();
        String email = m.mask("write to bob@acme.example please");
        assertThat(email).containsPattern("[a-z0-9]+@[a-z.]+\\.[a-z]+").doesNotContain("bob");
        String card = m.mask("card 4111111111111111 ok");
        assertThat(card).containsPattern("\\b4\\d{15}\\b").doesNotContain("4111111111111111");
        String phone = m.mask("call (415) 555-0132 now");
        assertThat(phone).containsPattern("\\(\\d{3}\\) \\d{3}-\\d{4}").doesNotContain("415) 555");
    }

    @Test
    void theSameValueGetsTheSameStandInWithinOneCaseAndDifferentValuesDiffer() {
        Masker m = new Masker();
        String a = m.mask("a@x.example then a@x.example then b@x.example");
        String[] parts = a.split(" then ");
        assertThat(parts[0]).isEqualTo(parts[1]).isNotEqualTo(parts[2]);
    }

    @Test
    void secretsAreReplacedWhateverTheShapeAndTheKindsAreReportedNotTheValues() {
        Masker m = new Masker();
        for (String s : Hostile.SECRETS) {
            String out = m.mask("before " + s + " after");
            String bare = s.replaceFirst("^(Bearer |api_key=|password: )", "");
            assertThat(out).as(s).doesNotContain(bare).contains("‹secret›");
        }
        assertThat(m.kinds()).contains("secret");
        assertThat(m.kinds().toString()).doesNotContain("sk-").doesNotContain("@");
    }

    @Test
    void valuesInsideMapsAndListsAreMaskedToo() {
        Masker m = new Masker(List.of("Maria Gonzalez"));
        Object out =
                m.maskValue(
                        Map.of(
                                "who",
                                "Maria Gonzalez",
                                "list",
                                List.of("mail bob@acme.example", 7, true)));
        assertThat(out.toString()).doesNotContain("Maria").doesNotContain("bob@");
        assertThat(out.toString()).contains("7").contains("true");
    }

    @Test
    void aMegabyteOfTextIsMaskedAndEmptyAndNullPassThrough() {
        Masker m = new Masker();
        String big = ("filler text with a@b.example in it. ").repeat(30_000);
        String out = m.mask(big);
        assertThat(out).doesNotContain("a@b.example");
        assertThat(m.mask(null)).isNull();
        assertThat(m.mask("")).isEmpty();
    }
}
