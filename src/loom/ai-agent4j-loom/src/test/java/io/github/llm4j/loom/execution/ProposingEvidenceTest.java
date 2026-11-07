package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.RunJournal;
import org.junit.jupiter.api.Test;

/** What a decision's proposal gathers as evidence, checked on its own without a whole run. */
class ProposingEvidenceTest {

    private static Decider.Proposing proposing() {
        return new Decider.Proposing(RunJournal.inMemory(), "step-1", false);
    }

    @Test
    void hasNoEvidenceUntilAToolResultIsRecorded() {
        Decider.Proposing p = proposing();

        assertThat(p.hasEvidence()).isFalse();

        p.record("search", "args-1", "three results");

        assertThat(p.hasEvidence()).isTrue();
    }

    @Test
    void aResultOverTheLimitIsNotRecordedAsEvidence() {
        Decider.Proposing p = proposing();

        p.record("search", "args-1", "x".repeat(Decider.EVIDENCE_LIMIT + 1));

        assertThat(p.hasEvidence()).isFalse();
    }

    @Test
    void evidenceRecordedBeforeTheLimitIsKeptWhenALaterResultOverflows() {
        Decider.Proposing p = proposing();

        p.record("search", "args-1", "x".repeat(Decider.EVIDENCE_LIMIT));
        p.record("search", "args-2", "one more");

        assertThat(p.hasEvidence()).isTrue();
    }
}
