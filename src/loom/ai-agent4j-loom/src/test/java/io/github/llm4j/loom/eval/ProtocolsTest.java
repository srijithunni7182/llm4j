package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 19 R14: the run protocol (what runs on a change, nightly, at a release gate, and as the
 * weekly drift check) is written down in one file, {@code eval-protocol.yaml}, and each named
 * protocol is a set of options of {@code weave eval}.
 */
class ProtocolsTest {
    @TempDir Path dir;

    private Map<String, Protocols.Protocol> load(String yaml) throws Exception {
        Files.writeString(dir.resolve("eval-protocol.yaml"), yaml);
        return Protocols.load(dir);
    }

    @Test
    void eachProtocolIsASetOfEvalOptions() throws Exception {
        Map<String, Protocols.Protocol> p =
                load(
                        "protocols:\n"
                                + "  commit:  { split: validation, repetitions: 3, max-cost: 1.00 }\n"
                                + "  release: { repetitions: 10, release-gate: true, judge-samples: 3 }\n"
                                + "  drift:   { split: validation, repetitions: 5, schedule: weekly, pinned: true }\n");

        assertThat(p.keySet()).containsExactly("commit", "release", "drift");
        assertThat(p.get("commit").split()).isEqualTo("validation");
        assertThat(p.get("commit").repetitions()).isEqualTo(3);
        assertThat(new java.math.BigDecimal(p.get("commit").maxCost())).isEqualByComparingTo("1.00");
        assertThat(p.get("release").releaseGate()).isTrue();
        assertThat(p.get("release").judgeSamples()).isEqualTo(3);
        assertThat(p.get("drift").schedule()).isEqualTo("weekly");
        assertThat(p.get("drift").pinned()).isTrue();
    }

    @Test
    void noFileMeansNoProtocols() throws Exception {
        assertThat(Protocols.load(dir)).isEmpty();
    }

    @Test
    void anUnknownKeyIsRefusedByNameNotIgnored() {
        assertThatThrownBy(() -> load("protocols:\n  nightly: { repetition: 5 }\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nightly")
                .hasMessageContaining("repetition")
                .hasMessageContaining("repetitions");
    }

    @Test
    void aSplitThatIsNotASplitAndARepetitionOutOfRangeAreRefused() {
        assertThatThrownBy(() -> load("protocols:\n  a: { split: validate }\n"))
                .hasMessageContaining("split").hasMessageContaining("validate");
        assertThatThrownBy(() -> load("protocols:\n  a: { repetitions: 11 }\n"))
                .hasMessageContaining("repetitions").hasMessageContaining("1 to 10");
    }

    @Test
    void aReleaseProtocolThatReadsHeldOutCasesMustSayItIsAGate() {
        assertThatThrownBy(() -> load("protocols:\n  oops: { split: heldout }\n"))
                .hasMessageContaining("heldout").hasMessageContaining("release-gate");
    }

    @Test
    void aFileThatIsNotAMapOfProtocolsIsRefused() {
        assertThatThrownBy(() -> load("- just\n- a list\n")).hasMessageContaining("protocols");
    }
}
