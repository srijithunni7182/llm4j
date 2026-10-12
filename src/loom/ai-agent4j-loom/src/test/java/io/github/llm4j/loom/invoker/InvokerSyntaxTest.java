package io.github.llm4j.loom.invoker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.autonomy.AgentIdentity;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.InvokerRefs;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Writing {@code invoker.NAME} in a script: what parses, what loads, and where it is refused. */
class InvokerSyntaxTest {

    @TempDir
    Path dir;

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private HarnessExecutor executor(String source) {
        HarnessExecutor e = new HarnessExecutor(parse(source), new ToolRegistry(), x -> null);
        e.setEnvLookup(Map.<String, String>of()::get);
        e.setBaseDir(dir);
        return e;
    }

    @Test
    @Tag("CC-V1.1")
    void invokerNameParsesToAnInvokerValue() {
        ToolDef tool = parse("tool T { use: openapi  spec: \"s.json\"  auth_header: \"H\"  auth_value: invoker.token }").getTools().get(0);

        ToolDef.OptionValue value = tool.getOptions().get("auth_value");

        assertThat(value.fromInvoker()).isTrue();
        assertThat(value.isReference()).isTrue();
        assertThat(value.fromEnv()).isFalse();
        assertThat(value.fromSecret()).isFalse();
        assertThat(value.value()).isEqualTo("token");
        assertThat(value).hasToString("invoker.token");
        assertThat(value.describe()).isEqualTo("invoker credential token");
        assertThat(ToolDef.OptionValue.invoker("admin-1_x").value()).isEqualTo("admin-1_x");
        ToolDef hyphen = parse("tool T { use: openapi  spec: \"s.json\"  auth_header: \"H\"  auth_value: invoker.my_key2 }").getTools().get(0);
        assertThat(hyphen.getOptions().get("auth_value").value()).isEqualTo("my_key2");
    }

    @Test
    @Tag("CC-V1.2")
    void aBadInvokerNameIsAParseError() {
        for (String bad : new String[] {"invoker.a.b"}) {
            assertThatThrownBy(() -> parse("tool T { use: serpapi  api_key: " + bad + " }"))
                    .as(bad).hasMessageContaining("an invoker reference is invoker.NAME").hasMessageContaining(bad);
        }
    }

    @Test
    @Tag("CC-V1.2")
    void anEmptyInvokerNameIsAnError() {
        assertThatThrownBy(() -> parse("tool T { use: serpapi  api_key: invoker. }")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parse("tool T { use: serpapi  api_key: invoker.1x }")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @Tag("CC-V1.2")
    void theValueHintMentionsInvoker() {
        assertThatThrownBy(() -> parse("tool T { use: serpapi  api_key: }")).hasMessageContaining("invoker.NAME");
    }

    @Test
    @Tag("CC-V1.3")
    void invokerIsRefusedWhereAKindDoesNotTakeIt() {
        assertThatThrownBy(() -> executor("tool T { use: serpapi  api_key: invoker.token }\nagent A { model: \"m\" tools: [T] }").initialize())
                .isInstanceOf(LoomLoadException.class)
                .hasMessageContaining("api_key can't come from invoker.token for use: serpapi")
                .hasMessageContaining("auth_value of use: openapi");
        assertThatThrownBy(() -> executor("tool T { use: openapi  spec: invoker.s  auth_header: \"H\"  auth_value: \"x\" }").initialize())
                .hasMessageContaining("spec can't come from invoker.s for use: openapi");
        assertThatThrownBy(() -> executor("tool T { use: http  base_url: \"https://a.example\"  auth_header: \"H\"  auth_value: invoker.token }").initialize())
                .hasMessageContaining("auth_value can't come from invoker.token for use: http");
    }

    @Test
    @Tag("CC-V1.4")
    void invokerIsRefusedOnAProvider() {
        assertThatThrownBy(() -> executor("provider Box { use: anthropic  api_key: invoker.token }\nagent A { model: \"Box/x\" }").initialize())
                .isInstanceOf(LoomLoadException.class)
                .hasMessageContaining("api_key can't be invoker.token: invoker credentials are for tools, not for the model provider");
        assertThatThrownBy(() -> executor("provider Box { use: ollama  base_url: invoker.url }\nagent A { model: \"Box/x\" }").initialize())
                .hasMessageContaining("base_url can't be invoker.url");
    }

    @Test
    @Tag("CC-V1.5")
    void invokerIsRefusedOnAHeaderOption() {
        assertThatThrownBy(() -> executor("tool T { use: http  base_url: \"https://a.example\"  header.Authorization: invoker.token }").initialize())
                .hasMessageContaining("header.Authorization can't come from invoker.token (not yet)");
    }

    @Test
    @Tag("CC-V1.6")
    void aLiteralCredentialIsStillRefusedAndTheMessageOffersInvoker() {
        assertThatThrownBy(() -> executor("tool T { use: openapi  spec: \"s.json\"  auth_header: \"H\"  auth_value: \"Bearer abc\" }").initialize())
                .hasMessageContaining("auth_value must come from the environment or the secret store")
                .hasMessageContaining("or invoker.NAME");
        assertThatThrownBy(() -> executor("tool T { use: serpapi  api_key: \"abc\" }").initialize())
                .hasMessageContaining("api_key must come from the environment or the secret store")
                .hasMessageNotContaining("invoker");
    }

    @Test
    @Tag("CC-V1.6")
    void anInvokerCredentialStillNeedsItsHeaderOrQuery() {
        assertThatThrownBy(() -> executor("tool T { use: openapi  spec: \"s.json\"  auth_value: invoker.token }").initialize())
                .hasMessageContaining("auth_value goes with exactly one of auth_header or auth_query");
    }

    @Test
    @Tag("CC-V1.6")
    void anOpenApiToolWithAnInvokerCredentialLoads() throws IOException {
        try (InvokerHarness h = new InvokerHarness(dir)) {
            h.executor(InvokerHarness.SCRIPT).initialize();
            h.executor(InvokerHarness.SCRIPT.replace("auth_header: \"Authorization\"", "auth_query: \"access_token\"")).initialize();
        }
    }

    @Test
    @Tag("CC-V1.7")
    void anInvokerValueHasNoLookupKey() {
        assertThatThrownBy(() -> ToolDef.OptionValue.invoker("token").lookupKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invoker.token");
        assertThat(ToolDef.OptionValue.env("K").lookupKey()).isEqualTo("K");
        assertThat(ToolDef.OptionValue.secret("K").lookupKey()).isEqualTo("secret:K");
    }

    /**
     * Every place that turns a reference into a lookup is reviewed to be unreachable for {@code invoker.NAME}: it is checked for it first, or the
     * value cannot come from a script. A new one must be reviewed the same way, so it has to be added here.
     */
    @Test
    @Tag("CC-V1.7")
    void everyLookupOfAReferenceHasBeenReviewedForInvoker() throws IOException {
        Map<String, Long> expected = Map.of(
                "ast/ToolDef.java", 1L,                 // the declaration (throws for invoker)
                "tools/ToolFactory.java", 4L,           // defaults of kinds; after the invoker check; after the invoker check; resolve() (invoker handled first)
                "execution/HarnessExecutor.java", 2L);  // credentialValue (invoker handled first); option() (providers are refused invoker at load)
        Path root = Path.of("src/main/java/io/github/llm4j/loom");
        try (Stream<Path> files = Files.walk(root)) {
            Map<String, Long> found = files.filter(p -> p.toString().endsWith(".java")).collect(java.util.stream.Collectors.toMap(
                    p -> root.relativize(p).toString(), p -> count(p), Long::sum)).entrySet().stream()
                    .filter(e -> e.getValue() > 0).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(found).isEqualTo(expected);
        }
    }

    private static long count(Path file) {
        try {
            String text = Files.readString(file);
            long n = 0;
            for (int i = text.indexOf("lookupKey()"); i >= 0; i = text.indexOf("lookupKey()", i + 1)) n++;
            return n;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String identity(String credential) {
        LoomScript script = parse("""
                tool T { use: openapi  spec: "s.json"  auth_header: "H"  auth_value: %s }
                agent Triager { model: "m"  system: "You are Triager."  tools: [T] }
                decision Refund { proposed by: Triager  choices: approve, reject }
                workflow W() { decide Refund -> v }
                """.formatted(credential));
        return AgentIdentity.of(script, script.getDecisions().get(0), dir);
    }

    @Test
    @Tag("CC-V1.9")
    void switchingACredentialToInvokerChangesTheAgentButRunningAgainDoesNot() {
        String env = identity("env.K");
        String invoker = identity("invoker.k");

        assertThat(env).isNotEqualTo(invoker);
        assertThat(identity("invoker.k")).isEqualTo(invoker);
        assertThat(identity("invoker.other")).isNotEqualTo(invoker);
        assertThat(identity("secret.K")).isNotEqualTo(env);
    }

    @Test
    @Tag("CC-V1.8")
    void theNamesAScriptAsksForAreListedSortedAndOnce() {
        LoomScript script = parse("""
                tool A { use: openapi  spec: "a.json"  auth_header: "H"  auth_value: invoker.zeta }
                tool B { use: openapi  spec: "b.json"  auth_query: "q"  auth_value: invoker.alpha }
                tool C { use: openapi  spec: "c.json"  auth_header: "H"  auth_value: invoker.alpha }
                tool D { use: serpapi  api_key: env.K }
                """);

        assertThat(InvokerRefs.names(script)).containsExactly("alpha", "zeta");
        assertThat(InvokerRefs.names(parse("tool D { use: serpapi  api_key: env.K }"))).isEmpty();
    }
}
