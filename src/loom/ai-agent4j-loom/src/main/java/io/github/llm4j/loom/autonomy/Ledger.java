package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The append-only record of cases, proposals, verdicts, outcomes and level changes of a decision. Implementations differ only in where the
 * records live (memory, a file per decision, a database table); all of them pass the same contract test.
 */
public interface Ledger {

    /** Adds a record. A record with an id that is already present is not added again. */
    void append(Rec record);

    /** Every record of the decision, in the order they were appended. */
    List<Rec> records(String decision);

    /** Removes the fields of cases older than {@code before}, keeping only that they happened (counts survive). */
    void purgeFields(String decision, Instant before);

    /** Lines of the stored data that could not be read (a torn write), for {@code status} to report. */
    default int unreadable(String decision) {
        return 0;
    }

    /** The records of these kinds, in the order they were appended (promotion proposals and their answers, say). */
    default List<Rec> recordsOfKind(String decision, String... kinds) {
        java.util.Set<String> wanted = java.util.Set.of(kinds);
        return records(decision).stream().filter(r -> wanted.contains(r.kind())).toList();
    }

    default List<Case> cases(String decision) {
        return Cases.fold(records(decision));
    }

    default List<Case> cases(String decision, Predicate<Case> filter) {
        return cases(decision).stream().filter(filter).toList();
    }

    /** The current (latest generation) case with this id. */
    default Optional<Case> get(String decision, String caseId) {
        return cases(decision).stream().filter(c -> c.id().equals(caseId) && !c.superseded()).findFirst();
    }
}
