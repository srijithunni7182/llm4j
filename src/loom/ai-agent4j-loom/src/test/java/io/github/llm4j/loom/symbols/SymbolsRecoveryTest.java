package io.github.llm4j.loom.symbols;

import static io.github.llm4j.loom.symbols.SymbolsFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R27 of spec 10: a half-typed script still gives what it can. */
class SymbolsRecoveryTest {

    @TempDir Path dir;

    private static final String GOOD =
            "agent Writer { model: \"m\" output_schema: { title: string } }\n"
                    + "agent Critic { model: \"m\" }\n"
                    + "workflow Main(topic) {\n"
                    + "  delegate \"Write {topic}\" to Writer -> draft\n"
                    + "  return draft\n"
                    + "}\n";

    @Test
    void aBrokenWorkflowDoesNotHideTheAgents() throws Exception {
        String text = GOOD + "workflow Broken(x) {\n  delegate \"half\" to \n}\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(i.problems()).isNotEmpty();
        assertThat(def(i, "agent:Writer")).isNotNull();
        assertThat(def(i, "agent:Critic")).isNotNull();
        assertThat(def(i, "workflow:Main")).isNotNull();
        assertThat(def(i, "workflow:Broken")).isNotNull();
        // the good workflow keeps its typed variable: the agent's schema was read
        assertThat(binding(i, "binding:Main/draft").type().kind()).isEqualTo("object");
        assertThat(i.toJson().get("partial").asBoolean()).isTrue();
    }

    @Test
    void aWorkflowBeingTypedStillOffersItsEarlierVariables() throws Exception {
        String text =
                GOOD.replace(
                        "  return draft\n",
                        "  delegate \"Review {draft}\" to Critic -> \n  return dr\n");
        SymbolIndex i = index(dir, "main.loom", text);
        // `draft` was made before the half-typed line and is still known, with its type
        assertThat(binding(i, "binding:Main/draft").type().kind()).isEqualTo("object");
        assertThat(refs(i, "agent:Critic")).hasSize(1);
        assertThat(def(i, "agent:Writer")).isNotNull();
    }

    @Test
    void aStrayCharacterIsSkipped() throws Exception {
        String text = GOOD + "agent Odd { model: \"m\" @ }\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(i.problems()).isNotEmpty();
        assertThat(def(i, "agent:Writer")).isNotNull();
        assertThat(def(i, "agent:Odd")).isNotNull();
    }

    @Test
    void anUnterminatedStringDoesNotEmptyTheAnswer() throws Exception {
        String text = GOOD + "agent Open { model: \"m system: \"never closed\n}\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(i.problems()).isNotEmpty();
        assertThat(def(i, "agent:Writer")).isNotNull();
        assertThat(def(i, "workflow:Main")).isNotNull();
    }

    @Test
    void aMissingClosingBraceKeepsEverythingBeforeIt() throws Exception {
        String text = GOOD + "agent Unclosed { model: \"m\"\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(i.problems()).isNotEmpty();
        assertThat(def(i, "agent:Writer")).isNotNull();
        assertThat(def(i, "workflow:Main")).isNotNull();
        assertThat(def(i, "agent:Unclosed")).isNotNull();
    }

    @Test
    void aStrayWordBetweenBlocksIsReportedAndSkipped() throws Exception {
        String text = GOOD + "banana\nagent After { model: \"m\" }\n";
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(i.problems()).isNotEmpty();
        assertThat(def(i, "agent:After")).isNotNull();
        assertThat(def(i, "agent:Writer")).isNotNull();
    }

    @Test
    void anEmptyFileIsAnEmptyIndexNotAnError() throws Exception {
        SymbolIndex i = index(dir, "main.loom", "");
        assertThat(i.definitions()).isEmpty();
        assertThat(i.problems()).isEmpty();
    }

    @Test
    void aSyntaxErrorInAnImportDoesNotHideTheEntry() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("lib.loom"), "agent Lib { model: \n");
        SymbolIndex i = index(dir, "main.loom", "import \"lib.loom\"\n" + GOOD);
        assertThat(def(i, "agent:Writer")).isNotNull();
        assertThat(i.problems()).anyMatch(p -> p.file().endsWith("lib.loom"));
    }

    @Test
    void unsavedTextIsWhatIsIndexed() throws Exception {
        Path file = dir.resolve("main.loom");
        java.nio.file.Files.writeString(file, "agent OnDisk { model: \"m\" }\n");
        var texts = new java.util.HashMap<Path, String>();
        texts.put(file, "agent InBuffer { model: \"m\" }\n");
        SymbolIndex i =
                io.github.llm4j.loom.execution.Buffers.with(
                        texts, new java.util.HashSet<>(), () -> new SymbolService().index(file));
        assertThat(i.definitions())
                .extracting(SymbolIndex.Definition::name)
                .containsExactly("InBuffer");
    }
}
