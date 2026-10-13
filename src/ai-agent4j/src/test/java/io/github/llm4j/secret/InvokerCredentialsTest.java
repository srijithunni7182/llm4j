package io.github.llm4j.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class InvokerCredentialsTest {

    @Test
    @Tag("CC-V4.1")
    void noneProvidesNothing() {
        InvokerCredentials none = InvokerCredentials.none();

        assertThat(none.get("token")).isNull();
        assertThat(none.provides("token")).isFalse();
    }

    @Test
    @Tag("CC-V2.1")
    void fixedValuesAreReturnedByName() {
        InvokerCredentials fixed = InvokerCredentials.of(Map.of("token", "Bearer t1", "admin", "Bearer a1"));

        assertThat(fixed.get("token")).isEqualTo("Bearer t1");
        assertThat(fixed.get("admin")).isEqualTo("Bearer a1");
        assertThat(fixed.get("other")).isNull();
        assertThat(fixed.provides("token")).isTrue();
        assertThat(fixed.provides("other")).isFalse();
    }

    @Test
    @Tag("CC-V2.4")
    void aSuppliedValueIsReadOnEveryCall() {
        AtomicInteger n = new AtomicInteger();
        InvokerCredentials supplied = InvokerCredentials.of("token", () -> "Bearer t" + n.incrementAndGet());

        assertThat(supplied.get("token")).isEqualTo("Bearer t1");
        assertThat(supplied.get("token")).isEqualTo("Bearer t2");
        assertThat(supplied.get("other")).isNull();
        assertThat(n).hasValue(2);
        assertThat(supplied.provides("token")).isTrue();
        assertThat(supplied.provides("other")).isFalse();
    }

    @Test
    @Tag("CC-V1.2")
    void namesFollowTheRulesOfSecretNames() {
        assertThatThrownBy(() -> InvokerCredentials.of("a.b", () -> "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvokerCredentials.of("1a", () -> "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvokerCredentials.of("token", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> InvokerCredentials.of((Map<String, String>) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @Tag("CC-V5.5")
    void toStringShowsNamesAndNeverValues() {
        InvokerCredentials fixed = InvokerCredentials.of(Map.of("token", "SECRETVALUE-9f3a"));
        InvokerCredentials supplied = InvokerCredentials.of("token", () -> "SECRETVALUE-9f3a");

        assertThat(fixed.toString()).contains("token").doesNotContain("SECRETVALUE");
        assertThat(supplied.toString()).contains("token").doesNotContain("SECRETVALUE");
    }

    @Test
    @Tag("CC-V2.5")
    void invalidateAndProvidesHaveHarmlessDefaults() {
        InvokerCredentials minimal = name -> "v";

        minimal.invalidate("token");

        assertThat(minimal.provides("anything")).isTrue();
    }

    @Test
    @Tag("CC-V2.5")
    void aSourceFromAStringReturnsItAndIgnoresRejection() {
        CredentialSource source = CredentialSource.of("Bearer t");

        source.rejected();

        assertThat(source.get()).isEqualTo("Bearer t");
        assertThatThrownBy(() -> CredentialSource.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @Tag("CC-V4.1")
    void theMissingCredentialExceptionNamesWhatIsMissing() {
        MissingInvokerCredentialException e = new MissingInvokerCredentialException(java.util.List.of("token", "admin"));

        assertThat(e.names()).containsExactly("token", "admin");
        assertThat(e.getMessage()).contains("[token, admin]").contains("InvokerCredentials");
        assertThat(e).isInstanceOf(SecretException.class);
    }
}
