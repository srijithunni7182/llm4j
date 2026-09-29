package io.github.llm4j.loom.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.KnowledgeDef;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.knowledge.DefaultEmbeddingFactory;
import io.github.llm4j.loom.knowledge.KnowledgeIndex;
import io.github.llm4j.loom.knowledge.KnowledgeIndexer;
import io.github.llm4j.loom.knowledge.Retriever;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.tools.ToolFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Branches of the new classes the scenario tests don't reach (coverage for N5). */
class ParityEdgeCasesTest {

    @TempDir
    Path dir;

    @Test
    void embeddingFactory() throws Exception {
        DefaultEmbeddingFactory withKey = new DefaultEmbeddingFactory(Map.of("GEMINI_API_KEY", "k")::get);
        assertThat(withKey.problem("gemini/text-embedding-004")).isNull();
        assertThat(withKey.create("gemini/text-embedding-004")).isNotNull();
        assertThat(withKey.problem(null)).contains("needs embedding");
        assertThat(withKey.problem(" ")).contains("needs embedding");
        assertThat(withKey.problem("plain")).contains("unknown embedding model");
        DefaultEmbeddingFactory blank = new DefaultEmbeddingFactory(Map.of("GEMINI_API_KEY", " ")::get);
        assertThat(blank.problem("gemini/x")).contains("GEMINI_API_KEY");
        assertThatThrownBy(() -> blank.create("gemini/x")).hasMessageContaining("GEMINI_API_KEY");
        assertThatThrownBy(() -> withKey.create("onnx/missing.onnx|missing.json")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> withKey.create("djl/file:///no/such/model")).isInstanceOf(Exception.class);
    }

    @Test
    void approvalAnswers() throws Exception {
        Class<?> gate = Class.forName("io.github.llm4j.loom.execution.ApprovalGate");
        Method yes = gate.getDeclaredMethod("yes", String.class);
        yes.setAccessible(true);
        for (String a : List.of("yes", "Y", " OK ", "approve", "TRUE")) assertThat((boolean) yes.invoke(null, a)).as(a).isTrue();
        for (String a : new String[] {"no", "", "yes please", null}) assertThat((boolean) yes.invoke(null, a)).isFalse();
        Method key = gate.getDeclaredMethod("key", String.class, String.class, Map.class);
        key.setAccessible(true);
        assertThat(key.invoke(null, "S", "T", null)).isEqualTo(key.invoke(null, "S", "T", Map.of()));
        assertThat(key.invoke(null, "S", "T", Map.of("l", List.of(Map.of("b", 1, "a", 2)))))
                .isEqualTo(key.invoke(null, "S", "T", Map.of("l", List.of(new java.util.TreeMap<>(Map.of("a", 2, "b", 1))))));
    }

    @Test
    void approvalWithoutAThought() {
        List<String> questions = new java.util.ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register("Pub", new Tool() {
            @Override public String getName() { return "Pub"; }
            @Override public String getDescription() { return "d"; }
            @Override public String execute(Map<String, Object> args) { return "ok"; }
        });
        io.github.llm4j.LLMClient model = new io.github.llm4j.LLMClient() {
            int n;
            @Override public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest r) {
                String c = n++ == 0 ? "```json\n{\"action\": \"Pub\", \"action_input\": {}}\n```" : ToolsTest.done("x");
                return io.github.llm4j.model.LLMResponse.builder().content(c).model("m").build();
            }
            @Override public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest r) {
                return java.util.stream.Stream.of(chat(r));
            }
        };
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer("""
                agent A { model: "m" tools: [Pub] approve: [Pub] }
                workflow Main() { delegate "go" to A -> r }
                """).tokenize()).parseScript(), registry, x -> model);
        e.setHumanInterface(m -> { questions.add(m); return "y"; });
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(questions).singleElement().satisfies(q -> assertThat(q).doesNotContain("Reason:"));
    }

    @Test
    void knowledgeEdgeCases() throws Exception {
        Files.writeString(dir.resolve("a.htm"), "<div>Alpha &amp; beta&nbsp;gamma &lt;tag&gt;</div><style>x{}</style>");
        Files.writeString(dir.resolve("empty.md"), "   ");
        Files.writeString(dir.resolve("noext"), "ignored");
        KnowledgeDef def = new KnowledgeDef("K");
        def.setPath(dir.toString());
        def.setEmbeddingProvider("test/hash");
        def.setTopK(2);
        KnowledgeIndex index = KnowledgeIndexer.index(def, new HashingEmbeddingProvider(), dir);
        assertThat(index.stats().files()).isEqualTo(2);
        assertThat(index.stats().skipped()).isEqualTo(1);
        assertThat(index.stats().chunks()).isEqualTo(1); // the blank file has none
        Tool search = Retriever.searchTool(index);
        assertThat(search.execute(Map.of("query", "alpha beta"))).contains("Alpha & beta gamma <tag>").doesNotContain("x{}");
        assertThat(search.execute(Map.of())).startsWith("Error");
        assertThat(search.execute(Map.of("query", " "))).startsWith("Error");
        assertThat(search.getDescription()).contains("Searches the K knowledge base");

        KnowledgeDef single = new KnowledgeDef("S");
        single.setPath(dir.resolve("noext").toString());
        single.setEmbeddingProvider("test/hash");
        KnowledgeIndex nothing = KnowledgeIndexer.index(single, new HashingEmbeddingProvider(), dir);
        assertThat(nothing.stats().skipped()).isEqualTo(1);
        assertThat(Retriever.searchTool(nothing).execute(Map.of("query", "x"))).isEqualTo("No relevant passages found.");
        assertThat(new Retriever(List.of()).topK()).isEqualTo(4);
        assertThat(Retriever.format(List.of())).isEmpty();
        assertThat(nothing.stats().toString()).contains("index up to date");
    }

    @Test
    void guardrailsAreFoundWhereverTheyAre() {
        String nested = """
                agent A { model: "m" }
                workflow Main() {
                    delegate "x" to A -> x on_failure { guardrail (G1) { note "a" } }
                    loop until (x == "y") max 2 { guardrail (G2) { note "b" } } on_exhausted { guardrail (G3) { note "c" } }
                    for each i in items budget 10 tokens { guardrail (G4) { note "d" } } on_exhausted { guardrail (G5) { note "e" } }
                    alt (x == "y") { guardrail (G6) { note "f" } } else { guardrail (G7) { note "g" } }
                    parallel { guardrail (G8) { note "h" } }
                    guardrail (PII) { guardrail (G9) { note "i" } } on_violation { guardrail (G10) { note "j" } }
                }
                """;
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(nested).tokenize()).parseScript(), new ToolRegistry(), m -> null);
        List<ScriptValidator.Problem> problems = new ScriptValidator().validate(
                new LoomParser(new Lexer(nested).tokenize()).parseScript(), e.validationContext());
        assertThat(problems).hasSize(10);
        assertThat(new ScriptValidator.Problem(0, "x", "y", ScriptValidator.Severity.ERROR).toString()).isEqualTo("x: y");
    }

    @Test
    void toolFactoryEdgeCases() throws Exception {
        ToolFactory f = new ToolFactory();
        assertThat(f.kinds()).contains("serpapi", "openapi", "class");
        assertThat(ToolFactory.builtIn("nope")).isNull();
        ToolDef google = new ToolDef("G");
        google.setKind("google_search");
        google.getOptions().put("api_key", ToolDef.OptionValue.env("K"));
        google.getOptions().put("cx", ToolDef.OptionValue.literal("abc"));
        assertThat(f.problems(google, Map.of("K", "v")::get)).isEmpty();
        assertThat(f.create(google, Map.of("K", "v")::get, dir).getName()).isEqualTo("G");
        assertThat(f.problems(google, Map.<String, String>of()::get)).singleElement().asString().contains("K is not set");
        assertThat(ToolDef.OptionValue.env("K").toString()).isEqualTo("env.K");
        assertThat(ToolDef.OptionValue.literal("v").toString()).isEqualTo("v");

        Files.writeString(dir.resolve("api.json"), """
                {"openapi": "3.0.0", "info": {"title": "T", "version": "1", "description": "Test API"},
                 "servers": [{"url": "http://localhost:1"}],
                 "paths": {"/a": {"post": {"description": "Make an a",
                   "requestBody": {"content": {"application/json": {"schema": {"type": "object", "properties": {"name": {"type": "string"}}}}}},
                   "responses": {"200": {"description": "ok"}}}}}}
                """);
        ToolDef api = new ToolDef("Api");
        api.setKind("openapi");
        api.getOptions().put("spec", ToolDef.OptionValue.literal(dir.resolve("api.json").toString()));
        api.getOptions().put("auth_query", ToolDef.OptionValue.literal("key"));
        api.getOptions().put("auth_value", ToolDef.OptionValue.env("K"));
        assertThat(f.problems(api, Map.of("K", "v")::get)).isEmpty();
        Tool tool = f.create(api, Map.of("K", "v")::get, Path.of("/elsewhere"));
        assertThat(tool.getDescription()).contains("Calls the T API (Test API)").contains("POST /a: Make an a");
        assertThat(tool.requiresApproval(Map.of())).isFalse();
        assertThat(((io.github.llm4j.loom.tools.NamedTool) tool).delegate()).isNotNull();
    }
}
