package io.github.llm4j.tools.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.tools.MailAddress;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MailAddressTest {

    @Test
    @Tag("V5.2")
    void plainAndNamedAddressesAreParsedAndTheDomainIsLowerCased() {
        assertThat(MailAddress.parse("User@Example.COM")).isEqualTo(new MailAddress(null, "User@example.com"));
        MailAddress named = MailAddress.parse("  Ann Lee <ann.lee+tag@example.com> ");
        assertThat(named.display()).isEqualTo("Ann Lee");
        assertThat(named.address()).isEqualTo("ann.lee+tag@example.com");
        assertThat(named.formatted()).isEqualTo("Ann Lee <ann.lee+tag@example.com>");
        assertThat(MailAddress.parse("a@localhost").formatted()).isEqualTo("a@localhost");
        assertThat(MailAddress.parse(" <a@example.com>").display()).as("an address in brackets alone has no name").isNull();
        assertThat(MailAddress.parse("  <a@example.com>").address()).isEqualTo("a@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "plain", "@example.com", "a@", "a@@example.com", "a b@example.com", "a@exa mple.com", "a..b@example.com",
            ".a@example.com", "a.@example.com", "a@-example.com", "a@example-.com", "a@exa_mple.com", "a@example..com", "<>", "Name <>", "<a@example.com",
            "a@example.com>", "Na<me <a@example.com>", "\"Q\" <a@example.com>", "a@example.com, b@example.com", "a@example.com; b@example.com",
            "a@example.com\r\nBcc: b@example.com", "a@example.com\nx", "a\u0085b@example.com", "a@exa\u2028mple.com", "a\u2029@example.com", "a\u0000@example.com", "a\u007f@example.com"})
    @Tag("V5.3")
    @Tag("H3")
    void anythingThatIsNotOneSinglePlainAddressIsRefused(String text) {
        assertThatThrownBy(() -> MailAddress.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("V5.3")
    void nullAndOverlongAddressesAreRefusedAndTheMessageStaysShort() {
        assertThatThrownBy(() -> MailAddress.parse(null)).hasMessageContaining("empty");
        String longLocal = "x".repeat(65) + "@example.com";
        assertThatThrownBy(() -> MailAddress.parse(longLocal)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailAddress.parse("x".repeat(250) + "@example.com")).hasMessageContaining("too long");
        assertThatThrownBy(() -> MailAddress.parse("not an address " + "y".repeat(100))).satisfies(e -> assertThat(e.getMessage().length()).isLessThan(100));
    }

    @Test
    @Tag("V5.2")
    void patternsMatchExactAddressesOrWholeDomainsAndNothingElse() {
        MailAddress a = MailAddress.parse("Ann@Example.com");
        assertThat(a.matches("ann@example.com")).isTrue();
        assertThat(a.matches("*@example.com")).isTrue();
        assertThat(a.matches(" *@EXAMPLE.com ")).isTrue();
        assertThat(a.matches("*@sub.example.com")).isFalse();
        assertThat(a.matches("*@example.org")).isFalse();
        assertThat(a.matches("bob@example.com")).isFalse();
        assertThat(MailAddress.parse("a@evilexample.com").matches("*@example.com")).isFalse();
        assertThat(MailAddress.parse("a@example.com.evil.net").matches("*@example.com")).isFalse();
    }

    @Test
    @Tag("V5.10")
    void onlyBareAddressesAndDomainWildcardsAreValidPatterns() {
        assertThat(MailAddress.validPattern("a@example.com")).isTrue();
        assertThat(MailAddress.validPattern("*@example.com")).isTrue();
        assertThat(MailAddress.validPattern("*@")).isFalse();
        assertThat(MailAddress.validPattern("*@ex ample.com")).isFalse();
        assertThat(MailAddress.validPattern("Name <a@example.com>")).as("a pattern is not a display name").isFalse();
        assertThat(MailAddress.validPattern("nonsense")).isFalse();
        assertThat(MailAddress.validPattern("*example.com")).isFalse();
    }
}
