package io.github.llm4j.loom.ast;

import io.github.llm4j.loom.autonomy.expr.Expr;

/**
 * {@code measure edit_size(c) = distance(c.proposal.value, c.response.final)}: a pure function of
 * one case, defined by the publisher.
 */
public class MeasureDef implements Node {
    private final String name;
    private final String param;
    private final Expr body;
    private final String text;
    private final int line;

    public MeasureDef(String name, String param, Expr body, String text, int line) {
        this.name = name;
        this.param = param;
        this.body = body;
        this.text = text;
        this.line = line;
    }

    public String getName() {
        return name;
    }

    /** The name the case goes by inside the body (the {@code c} in {@code measure m(c) = …}). */
    public String getParam() {
        return param;
    }

    public Expr getBody() {
        return body;
    }

    /** The body as written, for the rules-in-force record and for messages. */
    public String getText() {
        return text;
    }

    public int getLine() {
        return line;
    }
}
