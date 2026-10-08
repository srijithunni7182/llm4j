package io.github.llm4j.hexamind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchLibraryTest {

    private final SearchLibrary lib = SearchLibrary.load();

    @Test
    void fabricatedTermsFindNothing() {
        for (String q : List.of("QLL-7", "Quantum Lattice Ledger QLL-7 consensus protocol", "HyperQuantum Flux Ledger HQFL-9 standard")) {
            assertThat(lib.lookup(q)).as(q).isEmpty();
        }
    }

    @Test
    void everyEntryHasSnippetsAndRealTopicsAreFound() {
        assertThat(lib.ids()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(lib.lookup("Kyber ML-KEM TLS VPN performance benchmark overhead")).isNotEmpty();
        assertThat(lib.lookup("solid-state battery grid scale")).isNotEmpty();
        assertThat(lib.lookup("AI customer support cost reduction 60 percent")).isNotEmpty();
    }

    @Test
    void scenariosThatNeedEvidenceCanFindSome() {
        for (EvalScenario s : GoldenDataset.all()) {
            String kind = GoldenDataset.tag(s, "kind");
            boolean needsEvidence = kind != null && List.of("in-lane", "standard", "signature-behaviour", "signature-interaction", "tool-discipline", "fault-injection").contains(kind);
            boolean hasOwn = s.retrievalContext() != null && !s.retrievalContext().isEmpty();
            if (needsEvidence && !hasOwn) {
                assertThat(lib.lookup(s.input())).as(s.id() + ": " + s.input()).isNotEmpty();
            }
        }
    }

    @Test
    void toolServesScenarioResultsFirstAndSuppressesTerms() throws Exception {
        var t = lib.tool("WebSearch", List.of("own snippet"), List.of());
        assertThat(t.execute(Map.of("query", "anything"))).contains("own snippet");
        var s = lib.tool("Search", List.of(), List.of("QLL-7"));
        assertThat(s.execute(Map.of("query", "QLL-7 enterprise adoption"))).isEqualTo(SearchLibrary.NONE);
        assertThat(s.execute(Map.of("query", "ML-KEM TLS"))).contains("FIPS 203");
    }
}
