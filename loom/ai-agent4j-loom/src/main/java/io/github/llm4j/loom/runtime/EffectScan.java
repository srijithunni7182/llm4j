package io.github.llm4j.loom.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Finds the effects a run already performed in a stretch of its statements, in any attempt: what a rewind would cross. */
public final class EffectScan {

    private EffectScan() { }

    /** True when a step (without attempt suffixes) is at or after statement {@code from} of the block. */
    public static boolean inRegion(String step, String blockFree, int from) {
        if (!step.startsWith(blockFree)) return false;
        int i = blockFree.length();
        int j = i;
        while (j < step.length() && Character.isDigit(step.charAt(j))) j++;
        if (j == i) return false;
        return Integer.parseInt(step.substring(i, j)) >= from;
    }

    /** Descriptions of the effects done (or that may have been) in the statements from {@code from} on in {@code block}. */
    public static List<String> effectsIn(RunJournal journal, String block, int from) {
        String blockFree = Generations.strip(block);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
            String key = e.getKey();
            int marker = key.indexOf("#effect:");
            if (marker < 0) continue;
            String kind = e.getValue().kind();
            if (!"effect_done".equals(kind) && !"effect_pending".equals(kind)) continue;
            String step = Generations.strip(key.substring(0, marker));
            if (!inRegion(step, blockFree, from)) continue;
            String tool = key.substring(marker + "#effect:".length()).split(":", 2)[0];
            out.add(tool + " at " + step + ("effect_pending".equals(kind) ? " (outcome unknown)" : " (done)"));
        }
        return out;
    }

    /** The tool calls a person approved in those statements, in any attempt: what a rewind leaves behind and will not ask about again. */
    public static List<String> approvalsIn(RunJournal journal, String block, int from) {
        String blockFree = Generations.strip(block);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
            String key = e.getKey();
            int marker = key.indexOf("#approve:");
            if (marker < 0) continue;
            String answer = String.valueOf(e.getValue().value()).trim().toLowerCase(java.util.Locale.ROOT);
            if (!List.of("yes", "y", "ok", "approve", "true").contains(answer)) continue;
            String step = Generations.strip(key.substring(0, marker));
            if (inRegion(step, blockFree, from)) out.add(key.substring(marker + "#approve:".length()).split(":", 2)[0] + " at " + step);
        }
        return out;
    }
}
