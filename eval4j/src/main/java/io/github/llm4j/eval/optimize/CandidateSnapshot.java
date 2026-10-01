package io.github.llm4j.eval.optimize;

import java.util.Map;

/** A plain-data view of a {@link Candidate}, for JSON traces and checkpoints. */
public record CandidateSnapshot(
        String id,
        String parentId,
        Candidate.Origin origin,
        int round,
        Map<String, String> parameters) {

    static CandidateSnapshot of(Candidate c) {
        return new CandidateSnapshot(c.id(), c.parentId(), c.origin(), c.round(), c.parameters());
    }

    Candidate toCandidate() {
        return Candidate.restore(id, parentId, origin, round, parameters);
    }
}
