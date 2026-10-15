package io.github.llm4j.loom.ast;

/**
 * What to do with a step whose agent was stopped: {@code use partial}, {@code skip}, {@code discard}, {@code retry}, {@code suspend}, {@code conclude}
 * or {@code ask}. Written inside an {@code on_stopped { }} handler.
 */
public class OutcomeStmt extends LocatedStatement {

    /** The names match the runtime's outcomes. */
    public enum Kind {
        USE_PARTIAL("use partial"), SKIP("skip"), DISCARD("discard"), RETRY("retry"), SUSPEND("suspend"), CONCLUDE("conclude"), ASK("ask");

        private final String phrase;
        Kind(String phrase) { this.phrase = phrase; }
        /** As written in a script. */
        public String phrase() { return phrase; }

        public static Kind ofPhrase(String phrase) {
            for (Kind k : values()) if (k.phrase.equals(phrase)) return k;
            return null;
        }
    }

    private final Kind kind;

    public OutcomeStmt(Kind kind) { this.kind = kind; }

    public Kind getKind() { return kind; }
}
