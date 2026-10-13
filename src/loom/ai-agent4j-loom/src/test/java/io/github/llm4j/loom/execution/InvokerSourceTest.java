package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.secret.InvokerCredentials;
import io.github.llm4j.secret.SecretException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class InvokerSourceTest {

    private final AtomicReference<InvokerCredentials> current = new AtomicReference<>();
    private final InvokerSource source = new InvokerSource("token", current::get);

    @Test
    @Tag("CC-V2.3")
    void itAsksTheRunsInvokerEveryTimeAndKeepsNothing() {
        current.set(InvokerCredentials.of(Map.of("token", "Bearer one")));
        assertThat(source.get()).isEqualTo("Bearer one");

        current.set(InvokerCredentials.of(Map.of("token", "Bearer two")));
        assertThat(source.get()).isEqualTo("Bearer two");
    }

    @Test
    @Tag("CC-V4.1")
    void withoutARunsInvokerItSaysSoByName() {
        assertThatThrownBy(source::get).isInstanceOf(SecretException.class)
                .hasMessage("this run has no invoker credentials (invoker.token)");
    }

    @Test
    @Tag("CC-V2.8")
    void aCredentialTheInvokerLacksIsReportedByName() {
        current.set(InvokerCredentials.of(Map.of("other", "x")));

        assertThatThrownBy(source::get).isInstanceOf(SecretException.class).hasMessage("the invoker has no credential token");
    }

    @Test
    @Tag("CC-V2.5")
    void rejectionIsPassedToTheInvokerAndIsHarmlessWithout() {
        List<String> told = new ArrayList<>();
        current.set(new InvokerCredentials() {
            @Override
            public String get(String name) {
                return "v";
            }

            @Override
            public void invalidate(String name) {
                told.add(name);
            }
        });

        source.rejected();
        current.set(null);
        source.rejected();

        assertThat(told).containsExactly("token");
    }
}
