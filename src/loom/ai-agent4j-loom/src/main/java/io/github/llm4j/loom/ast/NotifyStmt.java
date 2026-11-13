package io.github.llm4j.loom.ast;

/**
 * {@code notify <handle> "name" ["text"] [sticky]} (a parent to a child it started) or {@code
 * notify parent "name" ["text"] [sticky]} (a child to the workflow that started it), phase 34c. A
 * signal is a named journal entry in the receiver's run. The text may use {@code {placeholders}};
 * the receiver always reads it as {@code unchecked}. A signal causes nothing by itself.
 */
public final class NotifyStmt extends LocatedStatement {
    private final HandleRef target;
    private final String name;
    private final String text;
    private final boolean sticky;

    /**
     * @param target the child's handle, or null for {@code parent}
     * @param name the signal's name
     * @param text the payload as written, or null
     * @param sticky true for a latch that stays set and is read without being consumed
     */
    public NotifyStmt(HandleRef target, String name, String text, boolean sticky) {
        this.target = target;
        this.name = name;
        this.text = text;
        this.sticky = sticky;
    }

    /** The child signalled, or null when the signal goes to the parent. */
    public HandleRef getTarget() {
        return target;
    }

    /** True for {@code notify parent}. */
    public boolean toParent() {
        return target == null;
    }

    public String getName() {
        return name;
    }

    /** The payload as written, or null. */
    public String getText() {
        return text;
    }

    public boolean isSticky() {
        return sticky;
    }
}
