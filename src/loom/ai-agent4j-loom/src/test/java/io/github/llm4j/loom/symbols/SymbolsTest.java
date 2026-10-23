package io.github.llm4j.loom.symbols;

import static io.github.llm4j.loom.symbols.SymbolsFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R20 of spec 10: the index says what the language's own parser understands. */
class SymbolsTest {

    @TempDir Path dir;

    @Test
    void everyKindOfDefinitionIsFoundWithItsNameRange() throws Exception {
        SymbolIndex i = index(dir, "main.loom", TRIAGE);
        assertThat(i.problems()).isEmpty();
        for (String key :
                new String[] {
                    "tool:Mailer",
                    "classifier:Intent",
                    "agent:Replier",
                    "agent:Checker",
                    "workflow:Main",
                    "checkpoint:Main/start"
                }) {
            SymbolIndex.Definition d = def(i, key);
            assertThat(slice(TRIAGE, d.range())).isEqualTo(d.name());
        }
        assertThat(def(i, "agent:Replier").detail()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void referencesCarryTheirRoleAndResolve() throws Exception {
        SymbolIndex i = index(dir, "main.loom", TRIAGE);
        assertThat(refs(i, "agent:Replier"))
                .extracting(SymbolIndex.Reference::role)
                .containsExactly("delegate-target");
        assertThat(refs(i, "tool:Mailer"))
                .extracting(SymbolIndex.Reference::role)
                .containsExactly("tool-use");
        assertThat(refs(i, "classifier:Intent"))
                .extracting(SymbolIndex.Reference::role)
                .containsExactly("classify");
        assertThat(refs(i, "tool:Mailer")).allMatch(SymbolIndex.Reference::resolved);
        for (SymbolIndex.Reference r : i.references())
            if (!r.role().equals("use") && !r.kind().equals("file"))
                assertThat(slice(TRIAGE, r.range()))
                        .isEqualTo(
                                r.to().substring(r.to().indexOf(':') + 1).replaceFirst("^.*/", ""));
    }

    @Test
    void aNameNothingDefinesIsAnUnresolvedReference() throws Exception {
        String text =
                "workflow Main(x) {\n  delegate \"hi\" to Ghost -> r\n  run Lookup(a = x) -> found\n}\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(refs(i, "agent:Ghost")).singleElement().matches(r -> !r.resolved());
        assertThat(refs(i, "task:Lookup")).singleElement().matches(r -> !r.resolved());
    }

    @Test
    void aClassifierBindsARecordWithOneFieldPerQuestion() throws Exception {
        SymbolIndex i = index(dir, "main.loom", TRIAGE);
        SymType t = binding(i, "binding:Main/intent").type();
        assertThat(t.kind()).isEqualTo("object");
        // `unsure` is there because the classifier escalates below its confidence
        assertThat(t.fields().get("kind").values())
                .containsExactly("order_status", "refund", "other", "unsure");
        assertThat(t.fields().get("urgent").kind()).isEqualTo("yesno");
        assertThat(t.fields().get("confidence").kind()).isEqualTo("number");
        assertThat(binding(i, "binding:Main/intent").producedBy()).isEqualTo("intentclassify");
    }

    @Test
    void withoutAnEscalatingRuleThereIsNoUnsure() throws Exception {
        String text = TRIAGE.replace("    confidence at least 0.8 else escalate\n", "");
        SymType t = binding(index(dir, "main.loom", text), "binding:Main/intent").type();
        assertThat(t.fields().get("kind").values())
                .containsExactly("order_status", "refund", "other");
    }

    @Test
    void aDelegateResultHasTheAgentsOutputSchemaOrItsExpectingBlock() throws Exception {
        SymbolIndex i = index(dir, "main.loom", TRIAGE);
        assertThat(binding(i, "binding:Main/reply").type().kind()).isEqualTo("string");
        SymType v = binding(i, "binding:Main/verdict").type();
        assertThat(v.kind()).isEqualTo("object");
        assertThat(v.fields().get("ok").kind()).isEqualTo("boolean");
        assertThat(v.fields().get("score").kind()).isEqualTo("number");
        assertThat(v.fields().get("tags").kind()).isEqualTo("list");

        String expecting =
                "agent A { model: \"m\" }\nworkflow W(x) {\n"
                        + "  delegate \"go\" to A -> out expecting { score: number, notes: string }\n}\n";
        SymType e = binding(index(dir, "e.loom", expecting), "binding:W/out").type();
        assertThat(e.fields().keySet()).containsExactly("score", "notes");
    }

    @Test
    void whatCannotBeKnownIsUnknownNotGuessed() throws Exception {
        String text =
                "workflow W(x) {\n  run Fetch(a = x) -> data\n  call Other(x = x) -> r\n}\nworkflow Other(x) { return x }\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(binding(i, "binding:W/data").type().kind()).isEqualTo("unknown");
        assertThat(binding(i, "binding:W/r").type().kind()).isEqualTo("unknown");
        assertThat(binding(i, "binding:W/x").role()).isEqualTo("param");
        assertThat(i.scopes())
                .anyMatch(
                        s -> s.workflow().equals("W") && s.params().equals(java.util.List.of("x")));
    }

    @Test
    void humanPromptAndSetAndForEachBindVariables() throws Exception {
        String text =
                "workflow W(docs) {\n  human_prompt \"ok?\" -> answer\n  set total = 3\n  for each doc in docs {\n    note \"{doc}\"\n  }\n}\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(binding(i, "binding:W/answer").type().kind()).isEqualTo("string");
        assertThat(binding(i, "binding:W/total").type().kind()).isEqualTo("number");
        assertThat(binding(i, "binding:W/doc").role()).isEqualTo("loop");
    }

    @Test
    void usesOfAVariableIncludeConditionsAndPlaceholders() throws Exception {
        SymbolIndex i = index(dir, "main.loom", TRIAGE);
        // intent.kind in the condition; {message} and {reply} in prompts; `return reply`
        assertThat(refs(i, "binding:Main/intent")).hasSize(1);
        assertThat(refs(i, "binding:Main/message"))
                .hasSize(2); // the classified text and a placeholder
        assertThat(refs(i, "binding:Main/reply")).hasSize(2);
        for (SymbolIndex.Reference r : refs(i, "binding:Main/reply"))
            assertThat(slice(TRIAGE, r.range())).isEqualTo("reply");
        // the root of intent.kind, not the whole path
        assertThat(slice(TRIAGE, refs(i, "binding:Main/intent").get(0).range()))
                .isEqualTo("intent");
    }

    @Test
    void checkpointsAndRewindsAreTiedWithinTheirWorkflow() throws Exception {
        String text =
                "agent A { model: \"m\" }\nworkflow W(x) {\n  checkpoint first\n  delegate \"go\" to A -> r\n"
                        + "  rewind to first when (r == \"no\") at most 2 times\n}\n"
                        + "workflow V(x) {\n  checkpoint first\n}\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(refs(i, "checkpoint:W/first"))
                .singleElement()
                .matches(r -> r.resolved() && r.role().equals("rewind"));
        assertThat(def(i, "checkpoint:V/first").scope()).isEqualTo("V");
    }

    @Test
    void importsAreFollowedAndTiedToTheirFile() throws Exception {
        Files(dir.resolve("lib/agents.loom"), "agent Helper { model: \"m\" }\n");
        String main =
                "import \"lib/agents.loom\"\nworkflow W(x) {\n  delegate \"go\" to Helper -> r\n}\n";
        SymbolIndex i = index(dir, "main.loom", main);
        assertThat(def(i, "agent:Helper").file())
                .endsWith("lib/agents.loom".replace('/', java.io.File.separatorChar));
        assertThat(refs(i, "agent:Helper"))
                .singleElement()
                .matches(r -> r.resolved() && r.file().endsWith("main.loom"));
        assertThat(i.references())
                .anyMatch(
                        r ->
                                r.kind().equals("file")
                                        && r.resolved()
                                        && r.to().endsWith("agents.loom"));
    }

    @Test
    void anImportedFileThatIsMissingIsAnUnresolvedFileReference() throws Exception {
        SymbolIndex i = index(dir, "main.loom", "import \"nope.loom\"\nagent A { model: \"m\" }\n");
        assertThat(i.references()).anyMatch(r -> r.kind().equals("file") && !r.resolved());
        assertThat(def(i, "agent:A")).isNotNull();
    }

    @Test
    void promptFilesAreDefinitionsWhenTheyExist() throws Exception {
        Files(dir.resolve("prompts/writer.md"), "You write.\n");
        Files(dir.resolve("prompts/researcher/v2.md"), "You research.\n");
        String text =
                "agent W { model: \"m\" prompt: \"writer\" }\nagent R { model: \"m\" prompt: \"researcher@v2\" }\nagent X { model: \"m\" prompt: \"absent\" }\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(def(i, "prompt:writer").file()).endsWith("writer.md");
        assertThat(def(i, "prompt:researcher@v2").file()).endsWith("v2.md");
        assertThat(refs(i, "prompt:absent")).singleElement().matches(r -> !r.resolved());
        assertThat(slice(text, refs(i, "prompt:writer").get(0).range())).isEqualTo("writer");
    }

    @Test
    void aProviderPrefixOfAModelNameIsAReferenceOnlyWhenTheScriptDefinesIt() throws Exception {
        String text =
                "provider Scorer { use: jev api_key: env.K }\nagent A { model: \"Scorer/jev-latest\" }\nagent B { model: \"openai/gpt\" }\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(refs(i, "provider:Scorer"))
                .singleElement()
                .matches(r -> slice(text, r.range()).equals("Scorer"));
        assertThat(i.references()).noneMatch(r -> r.to().equals("provider:openai"));
    }

    @Test
    void decisionsPersonasAndSupervisionAreTied() throws Exception {
        String text =
                "agent Critic { model: \"m\" }\ndecision Refund {\n  proposed by: Critic\n  choices: approve, reject\n  ask: lead\n}\n"
                        + "workflow W(x) {\n  decide Refund -> choice\n}\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(refs(i, "agent:Critic"))
                .singleElement()
                .matches(r -> r.role().equals("proposer"));
        assertThat(refs(i, "decision:Refund"))
                .singleElement()
                .matches(r -> r.role().equals("decide"));
        assertThat(binding(i, "binding:W/choice").type().values())
                .containsExactly("approve", "reject");
    }

    @Test
    void theOutputIsOrderedAndStable() throws Exception {
        SymbolIndex a = index(dir, "main.loom", TRIAGE);
        SymbolIndex b = index(dir, "main.loom", TRIAGE);
        assertThat(a.toJson().toString()).isEqualTo(b.toJson().toString());
        assertThat(a.definitions())
                .isSortedAccordingTo(
                        java.util.Comparator.comparing(SymbolIndex.Definition::file)
                                .thenComparingInt(d -> d.range().line())
                                .thenComparingInt(d -> d.range().col()));
    }

    private static void Files(Path file, String text) throws Exception {
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, text);
    }
}
