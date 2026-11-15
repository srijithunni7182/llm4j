package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.tools.ToolRefusal;
import org.junit.jupiter.api.Test;

/** How a credential is held for one request (phase 36a, D8): per request, over https, never explained by the host's own words. */
class CredentialTest {

    @Test
    void noCredentialIsNotPresent() {
        assertThat(Credential.NONE.present()).isFalse();
        assertThat(new Credential("X-Key", null, "k", null).present()).isTrue();
        assertThat(new Credential(null, "key", "k", null).present()).isTrue();
    }

    @Test
    void aSourceIsAskedEachTimeAndTheTokenAloneIsAlsoHidden() {
        int[] n = {0};
        Credential c = new Credential("Authorization", null, null, () -> "Bearer token-" + (++n[0]));

        assertThat(c.hold(true).secret()).isEqualTo("Bearer token-1");
        Credential.Held second = c.hold(true);
        assertThat(second.secret()).isEqualTo("Bearer token-2");
        assertThat(second.toHide()).contains("Bearer token-2", "token-2");
        assertThat(second.inQuery()).isFalse();
        assertThat(c.canRefresh()).isTrue();
    }

    @Test
    void aSourceIsNeverUsedOverPlainHttp() {
        Credential c = new Credential("Authorization", null, null, CredentialSource.of("Bearer abc-123"));

        assertThatThrownBy(() -> c.hold(false)).isInstanceOf(ToolRefusal.class).hasMessageContaining("only sent over https");
    }

    @Test
    void aStaticValueNeedsNoSourceAndGoesInTheQueryWhenAsked() {
        Credential c = new Credential(null, "key", "static-value", null);

        Credential.Held held = c.hold(false);

        assertThat(held.inQuery()).isTrue();
        assertThat(held.name()).isEqualTo("key");
        assertThat(c.canRefresh()).isFalse();
        c.rejected(); // nothing to tell
    }

    @Test
    void aSourceThatCannotAnswerIsSaidWithoutTheHostsOwnWords() {
        Credential names = new Credential("A", null, null, () -> { throw new SecretException("secret FILES is not set"); });
        Credential leaks = new Credential("A", null, null, () -> { throw new IllegalStateException("the vault password is hunter2"); });
        Credential blank = new Credential("A", null, null, () -> "  ");

        assertThatThrownBy(() -> names.hold(true)).hasMessageContaining("secret FILES is not set");
        assertThatThrownBy(() -> leaks.hold(true)).hasMessageContaining("IllegalStateException").hasMessageNotContaining("hunter2");
        assertThatThrownBy(() -> blank.hold(true)).hasMessageContaining("no credential");
    }

    @Test
    void rejectedTellsTheSource() {
        int[] told = {0};
        Credential c =
                new Credential(
                        "A",
                        null,
                        null,
                        new CredentialSource() {
                            @Override
                            public String get() {
                                return "value";
                            }

                            @Override
                            public void rejected() {
                                told[0]++;
                            }
                        });

        c.rejected();

        assertThat(told[0]).isEqualTo(1);
    }
}
