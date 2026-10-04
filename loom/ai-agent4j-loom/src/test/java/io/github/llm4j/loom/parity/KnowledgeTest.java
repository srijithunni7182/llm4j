package io.github.llm4j.loom.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V5.1–V5.13. */
class KnowledgeTest {

    @TempDir
    Path dir;

    final HashingEmbeddingProvider embeddings = new HashingEmbeddingProvider();
    final List<String> tasks = Collections.synchronizedList(new ArrayList<>());
    final List<String> systems = Collections.synchronizedList(new ArrayList<>());
    final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger calls = new AtomicInteger();
    List<String> answers = List.of();

    @BeforeEach
    void fixture() throws Exception {
        Path kb = Files.createDirectories(dir.resolve("kb"));
        Files.writeString(kb.resolve("refunds.md"), "# Refunds\nRefunds are issued within 14 days of the return arriving.");
        Files.writeString(kb.resolve("shipping.txt"), "Orders ship in 2 business days from our warehouse.");
        Files.writeString(kb.resolve("page.html"), "<html><body><p>Returns need a receipt</p><script>var x=1;</script></body></html>");
        Files.write(kb.resolve("logo.png"), new byte[] {1, 2, 3});
    }

    LLMClient model() {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                systems.add(r.getMessages().get(0).getContent());
                tasks.add(r.getMessages().get(r.getMessages().size() - 1).getContent());
                int i = calls.getAndIncrement();
                String content = i < answers.size() ? answers.get(i) : ToolsTest.done("ok");
                return LLMResponse.builder().content(content).model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
    }

    HarnessExecutor executor(String source) {
        return executor(source, null);
    }

    HarnessExecutor executor(String source, java.util.function.Consumer<HarnessExecutor> configure) {
        LLMClient m = model();
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), new ToolRegistry(), x -> m);
        e.setBaseDir(dir);
        e.setEmbeddingFactory(model -> embeddings);
        e.setAuditLogger(new io.github.llm4j.audit.AuditLogger() {
            @Override public void logAgentDecision(io.github.llm4j.audit.AuditEvent event) { }
            @Override public void logToolExecution(String s, String t, String i, String o, java.time.Instant ts) { }
            @Override public void logPromptUsage(String s, String p, String v, java.time.Instant ts) { }
            @Override public void logConversationEvent(String s, String u, String type, Map<String, Object> data) {
                audit.add(type + " " + data);
            }
        });
        if (configure != null) configure.accept(e);
        e.initialize();
        return e;
    }

    static String script(String kbOptions, String task) {
        return """
                knowledge KB { source: "kb" chunk_size: 200 overlap: 20 embedding: "test/hash" top_k: 1 %s }
                agent Support { model: "m" knowledge: [KB] }
                workflow Main() { delegate "%s" to Support -> answer }
                """.formatted(kbOptions, task);
    }

    @Test
    void v5_1_theRightPassageIsInTheContext() {
        executor(script("", "How long do refunds take?")).executeWorkflow("Main", Map.of());
        assertThat(tasks.get(0)).contains("Question: Relevant knowledge").contains("(refunds.md)").contains("14 days")
                .doesNotContain("shipping.txt");
    }

    @Test
    void v5_2_anotherQuestionAnotherPassage() {
        executor(script("", "When will my order ship?")).executeWorkflow("Main", Map.of());
        assertThat(tasks.get(0)).contains("(shipping.txt)").contains("2 business days");
    }

    @Test
    void v5_3_htmlIsStrippedAndOtherFilesSkipped() {
        HarnessExecutor e = executor(script("top_k: 3", "What do returns need?"));
        e.executeWorkflow("Main", Map.of());
        assertThat(tasks.get(0)).contains("(page.html) Returns need a receipt").doesNotContain("<p>").doesNotContain("var x");
        assertThat(e.getKnowledge("KB").stats().skipped()).isEqualTo(1);
        assertThat(e.getKnowledge("KB").stats().files()).isEqualTo(3);
        assertThat(audit).anySatisfy(a -> assertThat(a).startsWith("knowledge_indexed").contains("skipped=1").contains("files=3"));
    }

    @Test
    void v5_4_to_v5_7_aFileStoreIsIncremental() throws Exception {
        String source = script("store: \"idx/kb.json\"", "refunds?");
        executor(source);
        int first = embeddings.embedded.get();
        assertThat(first).isEqualTo(3); // one chunk per file
        embeddings.embedded.set(0);
        HarnessExecutor again = executor(source);
        assertThat(embeddings.embedded.get()).isZero(); // V5.4
        assertThat(again.getKnowledge("KB").stats().toString()).contains("index up to date");

        Files.writeString(dir.resolve("kb/refunds.md"), "Refunds are issued within 30 days now.");
        embeddings.embedded.set(0);
        executor(source);
        assertThat(embeddings.embedded.get()).isEqualTo(1); // V5.5: only refunds.md

        Files.delete(dir.resolve("kb/shipping.txt"));
        HarnessExecutor afterDelete = executor(source);
        assertThat(afterDelete.getKnowledge("KB").store().size()).isEqualTo(2); // V5.6
        tasks.clear();
        afterDelete.executeWorkflow("Main", Map.of());
        assertThat(tasks.get(0)).doesNotContain("shipping.txt");

        embeddings.embedded.set(0);
        HarnessExecutor rebuilt = executor(source.replace("chunk_size: 200", "chunk_size: 150"));
        assertThat(embeddings.embedded.get()).isEqualTo(2); // V5.7: full rebuild of what remains
        assertThat(rebuilt.getKnowledge("KB").stats().rebuilt()).isTrue();
    }

    @Test
    void v5_8_toolMode() {
        answers = List.of(ToolsTest.call("search_kb", "{\"query\": \"refunds\"}"), ToolsTest.done("14 days"));
        executor(script("mode: tool", "How long do refunds take?")).executeWorkflow("Main", Map.of());
        assertThat(tasks.get(0)).doesNotContain("Relevant knowledge");
        assertThat(systems.get(0)).contains("search_kb");
        assertThat(tasks.get(1)).contains("(refunds.md)").contains("14 days");
    }

    @Test
    void v5_9_aReplayedStepDoesNotRetrieve() {
        RunJournal journal = RunJournal.inMemory();
        executor(script("", "refunds?"), e -> e.setJournal(journal)).executeWorkflow("Main", Map.of());
        embeddings.embedded.set(0);
        HarnessExecutor replay = executor(script("", "refunds?"), e -> e.setJournal(journal));
        int afterIndex = embeddings.embedded.get();
        replay.executeWorkflow("Main", Map.of());
        assertThat(embeddings.embedded.get()).isEqualTo(afterIndex); // no query embedding on replay
    }

    @Test
    void v5_10_legacyPathAndType() {
        HarnessExecutor e = executor("""
                knowledge KB { type: "RAG" path: "kb" chunk_size: 200 embedding: "test/hash" }
                agent Support { model: "m" knowledge: [KB] }
                workflow Main() { delegate "refunds?" to Support -> answer }
                """);
        e.executeWorkflow("Main", Map.of());
        assertThat(tasks.get(0)).contains("Relevant knowledge");
        List<ScriptValidator.Problem> problems = new ScriptValidator().validate(
                new LoomParser(new Lexer("knowledge KB { type: \"RAG\" path: \"kb\" embedding: \"test/hash\" }").tokenize()).parseScript(),
                e.validationContext());
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.severity()).isEqualTo(ScriptValidator.Severity.WARNING);
            assertThat(p.message()).contains("type: is no longer used");
        });
    }

    @Test
    void v5_11_badSourcesAndEmbeddings() {
        assertThatThrownBy(() -> executor("knowledge K { source: \"missing\" embedding: \"test/hash\" }"))
                .hasMessageContaining("source missing does not exist");
        LLMClient m = model();
        HarnessExecutor real = new HarnessExecutor(new LoomParser(new Lexer("""
                knowledge K1 { source: "kb" embedding: "nope/x" }
                knowledge K2 { source: "kb" embedding: "gemini/text-embedding-004" }
                knowledge K3 { source: "kb" }
                knowledge K4 { source: "kb" embedding: "test/hash" chunk_size: 50 overlap: 50 }
                """).tokenize()).parseScript(), new ToolRegistry(), x -> m);
        real.setBaseDir(dir);
        real.setEnvLookup(Map.<String, String>of()::get);
        assertThatThrownBy(real::initialize)
                .hasMessageContaining("line 1: knowledge K1: unknown embedding model \"nope/x\"")
                .hasMessageContaining("line 2: knowledge K2: embedding gemini/text-embedding-004 needs GEMINI_API_KEY (an environment variable or a secret)")
                .hasMessageContaining("line 3: knowledge K3: needs embedding:")
                .hasMessageContaining("line 4: knowledge K4: overlap must be smaller than chunk_size");
    }

    @Test
    void v5_11b_addonEmbeddingsAreRecognised() {
        io.github.llm4j.loom.knowledge.DefaultEmbeddingFactory f = new io.github.llm4j.loom.knowledge.DefaultEmbeddingFactory(k -> null);
        assertThat(f.problem("onnx/model.onnx|tokenizer.json")).isNull(); // the addons module is a Loom dependency
        assertThat(f.problem("onnx/model.onnx")).contains("onnx/<model.onnx>|<tokenizer.json>");
        assertThat(f.problem("djl/djl://ai.djl.huggingface/x")).isNull();
    }

    @Test
    void v5_12_aKnowledgeBaseWithNothingToIndex() throws Exception {
        Files.createDirectories(dir.resolve("images"));
        Files.write(dir.resolve("images/a.png"), new byte[] {1});
        HarnessExecutor e = executor("""
                knowledge Pics { source: "images" embedding: "test/hash" }
                agent A { model: "m" knowledge: [Pics] }
                workflow Main() { delegate "anything?" to A -> x }
                """);
        e.executeWorkflow("Main", Map.of());
        assertThat(tasks.get(0)).doesNotContain("Relevant knowledge");
        assertThat(new ScriptValidator().validate(new LoomParser(new Lexer("knowledge Pics { source: \"images\" embedding: \"test/hash\" }")
                .tokenize()).parseScript(), e.validationContext()))
                .singleElement().satisfies(p -> assertThat(p.message()).contains("has no text files to index"));
    }

    @Test
    void v5_13_embeddingsAreNotChargedToBudgets() {
        HarnessExecutor e = executor("budget { tokens: 100000 }\n" + script("", "refunds?"));
        e.executeWorkflow("Main", Map.of());
        assertThat(e.getRunBudget().spent().calls()).isEqualTo(1); // only the model call
        assertThat(embeddings.embedded.get()).isGreaterThan(1);
    }

    @Test
    void aSingleFileSourceAndMemoryStore() {
        HarnessExecutor e = executor("""
                knowledge One { source: "kb/refunds.md" embedding: "test/hash" store: memory }
                agent A { model: "m" knowledge: [One] }
                workflow Main() { delegate "refunds?" to A -> x }
                """);
        e.executeWorkflow("Main", Map.of());
        assertThat(tasks.get(0)).contains("(refunds.md)");
    }
}
