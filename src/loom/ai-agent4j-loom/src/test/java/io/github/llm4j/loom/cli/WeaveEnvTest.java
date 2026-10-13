package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.InvokerCredentials;
import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The environment of a command keeps the person a run is for, whatever else is changed on it afterwards. */
class WeaveEnvTest {

    private final InvokerCredentials invoker = InvokerCredentials.of(Map.of("token", "t"));

    private WeaveEnv base() {
        return new WeaveEnv(model -> null, message -> "", new PrintStream(System.out), new PrintStream(System.err), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), Map.<String, String>of()::get)
                .withInvoker(invoker);
    }

    @Test
    @Tag("CC-V6.4")
    void withInvokerSetsItAndNothingElseChanges() {
        WeaveEnv plain = new WeaveEnv(model -> null, message -> "", System.out, System.err, Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"));

        WeaveEnv with = plain.withInvoker(invoker);

        assertThat(plain.invoker()).isNull();
        assertThat(with.invoker()).isSameAs(invoker);
        assertThat(with.out()).isSameAs(plain.out());
        assertThat(with.weave()).isEqualTo(plain.weave());
    }

    @Test
    @Tag("CC-V6.4")
    void everyOtherWithKeepsTheInvoker() {
        WeaveEnv env = base();

        assertThat(env.withAskVia("telegram").invoker()).isSameAs(invoker);
        assertThat(env.withAskVia("telegram").askVia()).isEqualTo("telegram");
        assertThat(env.withAskVia(null)).isSameAs(env);
        assertThat(env.withSecrets(new InMemorySecretStore()).invoker()).isSameAs(invoker);
        assertThat(env.withSecrets(new InMemorySecretStore()).secrets()).isNotNull();
        assertThat(env.withSecrets(null)).isSameAs(env);
        assertThat(env.withEnvLookup(Map.of("A", "b")::get).invoker()).isSameAs(invoker);
        assertThat(env.withEnvLookup(Map.of("A", "b")::get).env().apply("A")).isEqualTo("b");
        assertThat(env.withWeave(List.of("other")).invoker()).isSameAs(invoker);
        assertThat(env.withWeave(List.of("other")).weave()).containsExactly("other");
        PromptSettings settings = PromptSettings.NONE;
        assertThat(env.withPrompts(settings).invoker()).isSameAs(invoker);
        assertThat(env.withPrompts(settings).prompts()).isSameAs(settings);
    }
}
