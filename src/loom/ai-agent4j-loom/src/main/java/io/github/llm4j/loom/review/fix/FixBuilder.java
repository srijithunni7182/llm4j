package io.github.llm4j.loom.review.fix;

import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.model.Fix;
import io.github.llm4j.loom.review.model.TextEdit;
import io.github.llm4j.loom.review.rule.FixTemplate;
import io.github.llm4j.loom.review.rule.Template;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns the fix a rule describes as data into the edits that make it, against the text of the
 * script and the line the finding is about. If an anchor cannot be found on that line (the
 * statement is written differently from what the fix expects), no fix is offered: a fix is never
 * guessed.
 */
public final class FixBuilder {

    private FixBuilder() {}

    public static Optional<Fix> build(
            FixTemplate template, Entity entity, FactBase facts, SourceText source, String file) {
        int line = entity.where().line();
        List<TextEdit> edits = new ArrayList<>();
        for (FixTemplate.Operation op : template.operations()) {
            Optional<TextEdit> edit = edit(op, entity, facts, source, file, line);
            if (edit.isEmpty()) return Optional.empty();
            edits.add(edit.get());
        }
        if (!SourceText.disjoint(edits)) return Optional.empty();
        return Optional.of(
                new Fix(
                        template.safety(),
                        Template.render(template.label(), entity, facts),
                        edits));
    }

    private static Optional<TextEdit> edit(
            FixTemplate.Operation op,
            Entity e,
            FactBase facts,
            SourceText src,
            String file,
            int line) {
        String text = op.text() == null ? "" : Template.render(op.text(), e, facts);
        String anchor = op.anchor() == null ? "" : op.anchor();
        if (anchor.startsWith("lineOf:")) {
            Object l = e.get(anchor.substring(7));
            line = l instanceof Number n ? n.intValue() : line;
            anchor = "lineEnd";
        }
        if (anchor.equals("fileStart")) return Optional.of(new TextEdit(file, 1, 1, 1, 1, text));
        if (line < 1 || line > src.lineCount()) return Optional.empty();
        switch (op.kind()) {
            case "insertAfter", "insertBefore" -> {
                int col;
                switch (anchor) {
                    case "statementEnd", "statementStart" -> col =
                            anchor.equals("statementEnd")
                                    ? src.statementEndColumn(line)
                                    : firstWord(src, line);
                    case "beforeBrace", "blockStart" -> col = src.beforeBraceColumn(line);
                    case "lineEnd", "line" -> col = src.lineEndColumn(line);
                    default -> col = -1;
                }
                if (col < 1) return Optional.empty();
                return Optional.of(new TextEdit(file, line, col, line, col, text));
            }
            case "replaceText" -> {
                String code = SourceText.code(src.line(line));
                String find = op.find();
                if (find == null || find.isEmpty()) return Optional.empty();
                int at = code.indexOf(find);
                if (at < 0) return Optional.empty();
                return Optional.of(
                        new TextEdit(file, line, at + 1, line, at + 1 + find.length(), text));
            }
            case "deleteLines" -> {
                int end = anchor.equals("block") ? src.blockEnd(line) : line;
                if (end < src.lineCount())
                    return Optional.of(new TextEdit(file, line, 1, end + 1, 1, ""));
                return Optional.of(
                        new TextEdit(file, line, 1, end, src.line(end).length() + 1, ""));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private static int firstWord(SourceText src, int line) {
        String l = src.line(line);
        int i = 0;
        while (i < l.length() && Character.isWhitespace(l.charAt(i))) i++;
        return i + 1;
    }
}
