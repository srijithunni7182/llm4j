package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * R6, R8 of spec 14, exactly: what a stand-in looks like, how they are numbered, which span wins.
 */
class MaskerStandInsTest {

    @Test
    void emailsAreNumberedInReadingOrderWhateverOrderTheyWereFoundIn() {
        Masker m = new Masker();
        assertThat(m.mask("b@x.example then a@x.example then b@x.example"))
                .isEqualTo(
                        "person1@example.invalid then person2@example.invalid then person1@example.invalid");
    }

    @Test
    void anEmailIsTheSameEmailInAnyCase() {
        Masker m = new Masker();
        assertThat(m.mask("Bob@X.Example and bob@x.example"))
                .isEqualTo("person1@example.invalid and person1@example.invalid");
    }

    @Test
    void aCardKeepsItsBrandDigitAndLengthAndTheCounterEndsIt() {
        Masker m = new Masker();
        assertThat(m.mask("4111111111111111")).isEqualTo("4000000000000001");
        assertThat(m.mask("5500005555555559")).isEqualTo("5000000000000002");
    }

    @Test
    void aPhoneNumberKeepsItsPunctuationAndLength() {
        Masker m = new Masker();
        assertThat(m.mask("call (415) 555-0132")).isEqualTo("call (000) 000-0001");
        assertThat(m.mask("or (415) 555-0199")).isEqualTo("or (000) 000-0002");
    }

    @Test
    void aCounterWithTwoDigitsTakesTheLastTwoPlaces() {
        Masker m = new Masker();
        String last = null;
        for (int i = 0; i < 12; i++) last = m.mask("(415) 555-" + String.format("%04d", 100 + i));
        assertThat(last).isEqualTo("(000) 000-0012");
    }

    @Test
    void whereTwoFindingsOverlapTheLongerOneWinsAndNothingOfItIsLeft() {
        Masker m = new Masker();
        String out = m.mask("call +1 415 555 0132 now");
        assertThat(out).startsWith("call ").endsWith(" now");
        assertThat(out).doesNotContain("415").doesNotContain("555").doesNotContain("0132");
        // one stand-in for the whole number, not two glued together
        assertThat(out.substring(5, out.length() - 4)).matches("[+\\d ]+");
        assertThat(out.replaceAll("[^0-9]", "")).hasSize(11);
    }

    @Test
    void termsAreNumberedLongestFirstAndANumberIsUsedEvenWhenItsTermIsAbsent() {
        Masker m = new Masker(List.of("Bo", "Maria Gonzalez", "Zed Absent Person"));
        assertThat(m.mask("Maria Gonzalez wrote to Bo")).isEqualTo("‹term2› wrote to ‹term3›");
        assertThat(m.kinds()).containsExactly("term");
    }

    @Test
    void aTermIsFoundInAnyCaseAndEveryTimeAndBlankTermsAreIgnored() {
        Masker m = new Masker(java.util.Arrays.asList("  ", null, "acme"));
        assertThat(m.mask("ACME and Acme and acme")).isEqualTo("‹term1› and ‹term1› and ‹term1›");
    }

    @Test
    void textWithNothingToMaskComesBackUnchangedAndReportsNothing() {
        Masker m = new Masker();
        assertThat(m.mask("the parcel shipped on Monday"))
                .isEqualTo("the parcel shipped on Monday");
        assertThat(m.mask("")).isEmpty();
        assertThat(m.mask(null)).isNull();
        assertThat(m.kinds()).isEmpty();
    }

    @Test
    void kindsNameWhatWasFoundInLowerCase() {
        Masker m = new Masker();
        m.mask("mail bob@acme.example or call (415) 555-0132");
        assertThat(m.kinds()).containsExactlyInAnyOrder("email", "phone");
    }
}
