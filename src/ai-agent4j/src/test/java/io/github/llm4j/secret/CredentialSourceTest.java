package io.github.llm4j.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialSourceTest {

    @Test
    void ofGivesTheSameValueEveryTime() {
        CredentialSource source = CredentialSource.of("Bearer abc");

        assertThat(source.get()).isEqualTo("Bearer abc");
        assertThat(source.get()).isEqualTo("Bearer abc");
    }

    @Test
    void beingRefusedDoesNothingUnlessTheSourceOverridesIt() {
        CredentialSource source = CredentialSource.of("v");

        source.rejected();

        assertThat(source.get()).isEqualTo("v");
    }

    @Test
    void aLambdaIsASource() {
        CredentialSource source = () -> "from-lambda";

        assertThat(source.get()).isEqualTo("from-lambda");
    }
}
