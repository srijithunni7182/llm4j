package io.github.llm4j.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies a list of (old text, new text) edits to one text, all or none (D11): each old text must
 * be found exactly once in the original; edits are placed against the original, not one after
 * another; edits that overlap are refused. A failure names the edit and why.
 */
final class PatchApplier {

    /** One edit: replace {@code old}, which must occur once, with {@code replacement}. */
    record Edit(String old, String replacement) {}

    private record Placed(int number, int start, int end, String replacement) {}

    private PatchApplier() {}

    static String apply(String original, List<Edit> edits) {
        if (edits.isEmpty()) throw new ToolRefusal("edits: give at least one {old, new}");
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            Edit e = edits.get(i);
            int number = i + 1;
            if (e.old().isEmpty()) throw new ToolRefusal("edit " + number + ": old is empty");
            int at = original.indexOf(e.old());
            if (at < 0) throw new ToolRefusal("edit " + number + ": the old text was not found");
            int count = occurrences(original, e.old());
            if (count > 1)
                throw new ToolRefusal(
                        "edit "
                                + number
                                + ": the old text was found "
                                + count
                                + " times; add more of the text around it so it is found once");
            placed.add(new Placed(number, at, at + e.old().length(), e.replacement()));
        }
        placed.sort(Comparator.comparingInt(Placed::start));
        for (int i = 1; i < placed.size(); i++) {
            Placed a = placed.get(i - 1);
            Placed b = placed.get(i);
            if (b.start() < a.end())
                throw new ToolRefusal(
                        "edits "
                                + Math.min(a.number(), b.number())
                                + " and "
                                + Math.max(a.number(), b.number())
                                + " overlap");
        }
        StringBuilder out = new StringBuilder(original.length());
        int from = 0;
        for (Placed p : placed) {
            out.append(original, from, p.start()).append(p.replacement());
            from = p.end();
        }
        return out.append(original.substring(from)).toString();
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }
}
