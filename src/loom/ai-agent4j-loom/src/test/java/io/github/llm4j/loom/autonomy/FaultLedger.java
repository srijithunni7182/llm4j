package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.List;

/** A ledger that fails: every read, every write, or the Nth write. */
final class FaultLedger implements Ledger {

    private final Ledger delegate;
    boolean failReads;
    boolean failWrites;
    int failWriteNumber;
    private int writes;

    FaultLedger(Ledger delegate) {
        this.delegate = delegate;
    }

    @Override
    public void append(Rec record) {
        writes++;
        if (failWrites || writes == failWriteNumber) throw new IllegalStateException("the ledger is down (write " + writes + ")");
        delegate.append(record);
    }

    @Override
    public List<Rec> records(String decision) {
        if (failReads) throw new IllegalStateException("the ledger is down (read)");
        return delegate.records(decision);
    }

    @Override
    public void purgeFields(String decision, Instant before) {
        delegate.purgeFields(decision, before);
    }
}
