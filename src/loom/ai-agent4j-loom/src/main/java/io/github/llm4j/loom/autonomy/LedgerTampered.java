package io.github.llm4j.loom.autonomy;

/** The record of levels, promotions and freezes does not verify: something other than the runtime has changed it. Nothing is trusted from it until an operator accepts it. */
public class LedgerTampered extends IllegalStateException {

    private final transient Seal.Result result;

    public LedgerTampered(String decision, Seal.Result result) {
        super("the record of levels of decision " + decision + " does not verify (" + result.why() + (result.record() == null ? "" : ", at " + result.record())
                + "): every case goes to a person until an operator looks and accepts it with weave autonomy verify --accept");
        this.result = result;
    }

    public Seal.Result result() {
        return result;
    }
}
