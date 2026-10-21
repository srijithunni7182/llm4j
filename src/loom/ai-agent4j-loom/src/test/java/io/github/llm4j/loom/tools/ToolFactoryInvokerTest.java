package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.secret.CredentialSource;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How the factory hands an {@code invoker.NAME} option to a kind: as a source to ask, never as a
 * value.
 */
class ToolFactoryInvokerTest {

    /** A kind that takes {@code token} from the invoker and records what it was given. */
    private static final class Recording implements ToolKind {
        Map<String, String> options;
        Map<String, CredentialSource> lateBound;

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public Set<String> optional() {
            return Set.of("token", "plain", "other");
        }

        @Override
        public Set<String> lateBound() {
            return Set.of("token");
        }

        @Override
        public Tool create(String name, Map<String, String> options, Path baseDir) {
            throw new AssertionError("the kind is built with its sources");
        }

        @Override
        public Tool create(
                String name,
                Map<String, String> options,
                Map<String, CredentialSource> lateBound,
                Path baseDir,
                EffectContext context) {
            this.options = new HashMap<>(options);
            this.lateBound = new HashMap<>(lateBound);
            return new CalculatorTool();
        }
    }

    private static ToolDef tool(String... options) {
        ToolDef def = new ToolDef("T");
        def.setKind("recording");
        for (int i = 0; i < options.length; i += 2) {
            String v = options[i + 1];
            def.getOptions()
                    .put(
                            options[i],
                            v.startsWith("invoker.")
                                    ? ToolDef.OptionValue.invoker(v.substring(8))
                                    : ToolDef.OptionValue.literal(v));
        }
        return def;
    }

    private final ToolFactory factory = new ToolFactory();
    private final Recording kind = new Recording();

    ToolFactoryInvokerTest() {
        factory.register(kind);
    }

    @Test
    @Tag("CC-V1.3")
    void anInvokerOptionIsHandedOverAsASourceAndLeftOutOfTheValues() throws Exception {
        CredentialSource source = CredentialSource.of("Bearer t");
        ToolDef def = tool("token", "invoker.token", "plain", "p");

        factory.create(
                def,
                name -> null,
                Path.of("."),
                EffectContext.noop(),
                name -> {
                    assertThat(name).isEqualTo("token");
                    return source;
                });

        assertThat(kind.lateBound).containsOnlyKeys("token");
        assertThat(kind.lateBound.get("token")).isSameAs(source);
        assertThat(kind.options).containsOnlyKeys("plain").containsEntry("plain", "p");
    }

    @Test
    @Tag("CC-V1.3")
    void withoutAnInvokerOptionTheKindGetsNoSources() throws Exception {
        Tool built = factory.create(tool("plain", "p"), name -> null, Path.of("."));

        assertThat(built.getName()).isEqualTo("T");
        assertThat(kind.lateBound).isEmpty();
        assertThat(kind.options).containsEntry("plain", "p");
    }

    @Test
    @Tag("CC-V4.1")
    void aToolBuiltOutsideARunCannotHaveInvokerCredentials() {
        assertThatThrownBy(
                        () ->
                                factory.create(
                                        tool("token", "invoker.token"), name -> null, Path.of(".")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tool T takes invoker.token, which only a run can supply");
        assertThatThrownBy(
                        () ->
                                factory.create(
                                        tool("token", "invoker.token"),
                                        name -> null,
                                        Path.of("."),
                                        EffectContext.noop()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tool T takes invoker.token, which only a run can supply");
    }

    @Test
    @Tag("CC-V1.3")
    void theOptionsAreCheckedWithTheInvokerTextStandingInForTheValue() {
        assertThat(factory.problems(tool("token", "invoker.token"), name -> null)).isEmpty();
        assertThat(factory.problems(tool("other", "invoker.token"), name -> null))
                .singleElement()
                .asString()
                .startsWith("other can't come from invoker.token for use: recording")
                .contains("auth_value of use: openapi");
        assertThat(factory.problems(tool("nope", "invoker.token"), name -> null))
                .singleElement()
                .asString()
                .contains("unknown option nope");
    }

    @Test
    @Tag("CC-V1.3")
    void aKindThatDoesNotListTheOptionAsLateBoundCannotBeGivenOne() throws Exception {
        ToolKind plain =
                new ToolKind() {
                    @Override
                    public String name() {
                        return "plain";
                    }

                    @Override
                    public Tool create(String name, Map<String, String> options, Path baseDir) {
                        return new CalculatorTool();
                    }
                };

        assertThat(plain.lateBound()).isEmpty();
        assertThatThrownBy(
                        () ->
                                plain.create(
                                        "T",
                                        Map.of(),
                                        Map.of("token", CredentialSource.of("x")),
                                        Path.of("."),
                                        EffectContext.noop()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("use: plain does not take [token] from the invoker");
        assertThat(plain.create("T", Map.of(), Map.of(), Path.of("."), EffectContext.noop()))
                .isNotNull();
    }
}
