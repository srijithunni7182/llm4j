package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.ToolProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 35 (D2): the kinds Loom registers itself say what they reach, and the built-in catalog the
 * audit asks is the one {@link ToolFactory} builds tools from.
 */
class LoomKindProfilesTest {

    private static ToolProfile profile(String kind, Map<String, String> options) {
        return ToolFactory.standardKind(kind).orElseThrow().profile(options);
    }

    @Test
    void theCatalogHasExactlyTheKindsAFactoryHas() {
        // a factory has the built-in kinds and those the jars on the class path bring: openapi is
        // the latter since phase 36 (the ai-agent4j-tools-openapi module), found with ServiceLoader
        java.util.Set<String> expected = new java.util.TreeSet<>(ToolFactory.standardKindNames());
        expected.addAll(new ToolFactory().jarKinds().keySet());
        assertThat(new ToolFactory().kinds()).isEqualTo(expected);
        assertThat(new ToolFactory().jarKinds()).containsOnlyKeys("openapi");
        assertThat(ToolFactory.standardKind("no_such_kind")).isEmpty();
    }

    @Test
    void searchKindsReadOnlyWebResults() {
        for (String kind : new String[] {"duckduckgo", "serpapi", "google_search"}) {
            ToolProfile p = profile(kind, Map.of());
            assertThat(p.untrusted()).as(kind).isTrue();
            assertThat(p.privateData() || p.outward()).as(kind).isFalse();
            assertThat(p.effect()).as(kind).isEqualTo(TaskEffect.READS);
            assertThat(p.note()).isEqualTo("web search results");
        }
    }

    @Test
    void localAndLanguageKindsReachNothingAndAreNamedInTheirNote() {
        for (String kind :
                new String[] {
                    "calculator",
                    "datetime",
                    "current_time",
                    "translate",
                    "transliterate",
                    "detect_language",
                    "speak",
                    "transcribe"
                }) {
            ToolProfile p = profile(kind, Map.of());
            assertThat(p.known()).as(kind).isTrue();
            assertThat(p.effect()).as(kind).isEqualTo(TaskEffect.NONE);
            assertThat(p.journaled()).as(kind).isFalse();
            assertThat(p.note()).isEqualTo(kind);
        }
    }

    @Test
    void openApiCanDoAnythingItsSpecOffersUnlessItNamesOneOperationAndItsEffect() {
        ToolProfile p =
                ToolFactory.knownKind("openapi")
                        .orElseThrow()
                        .profile(Map.of("spec", "specs/pets.json", "auth_value", "env.TOKEN"));
        assertThat(p.untrusted() && p.privateData() && p.outward()).isTrue();
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(p.note()).contains("any operation of specs/pets.json");
    }

    @Test
    void aKnowledgeGraphWritesUnlessReadOnly() {
        assertThat(profile("knowledge_graph", Map.of("store", "g.json")).effect())
                .isEqualTo(TaskEffect.CHANGES);
        ToolProfile readOnly =
                profile("knowledge_graph", Map.of("store", "g.json", "read_only", "true"));
        assertThat(readOnly.effect()).isEqualTo(TaskEffect.READS);
        assertThat(readOnly.privateData()).isTrue();
    }

    @Test
    void aSkillRegistryFetchesInstructions() {
        ToolProfile p = profile("skill_registry", Map.of("url", "https://skills.example.com"));
        assertThat(p.untrusted()).isTrue();
        assertThat(p.note()).isEqualTo("skills fetched from https://skills.example.com");
    }

    @Test
    void aClassToolIsWhatItsAuthorDeclaresOrUnknown() {
        assertThat(profile("class", Map.of("class", "a.B")).known()).isFalse();
        ToolProfile sends = profile("class", Map.of("class", "a.B", "reach", "sends"));
        assertThat(sends.outward()).isTrue();
        assertThat(sends.note())
                .isEqualTo(
                        "Java class a.B, declared reach: sends (the author's word; the audit does not check it)");
        assertThat(profile("class", Map.of("class", "a.B", "reach", "loud")).note())
                .isEqualTo(
                        "Java class a.B: reach: loud is not one of none, reads, fetches, writes, sends");
    }

    @Test
    void theGenericKindsAreJournaledThroughTheAdapter() {
        for (String kind : new String[] {"webhook", "email", "http", "file", "shell", "sql"})
            assertThat(profile(kind, Map.of()).journaled()).as(kind).isTrue();
    }

    @Test
    void aLoomKindThatDoesNotAnswerIsAssumedToReachEverything() {
        ToolKind silent =
                new ToolKind() {
                    @Override
                    public String name() {
                        return "mystery";
                    }

                    @Override
                    public io.github.llm4j.agent.Tool create(
                            String name, Map<String, String> options, java.nio.file.Path baseDir) {
                        throw new UnsupportedOperationException();
                    }
                };
        ToolProfile p = silent.profile(Map.of());
        assertThat(p.known()).isFalse();
        assertThat(p.untrusted() && p.privateData() && p.outward()).isTrue();
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(p.note()).contains("\"mystery\" does not say what it reaches");
    }
}
