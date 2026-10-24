package io.github.llm4j.loom.review.fix;

import java.util.ArrayList;
import java.util.List;

/** A unified diff of two texts, small and dependency free: enough for the diffs a fix produces. */
public final class UnifiedDiff {

    private UnifiedDiff() {}

    /** The diff, or an empty string when the texts are the same. */
    public static String of(String name, String before, String after) {
        if (before.equals(after)) return "";
        List<String> a = lines(before);
        List<String> b = lines(after);
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                lcs[i][j] =
                        a.get(i).equals(b.get(j))
                                ? lcs[i + 1][j + 1] + 1
                                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        // the edit script as lines marked ' ', '-' or '+'
        List<String> script = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) {
                script.add(" " + a.get(i));
                i++;
                j++;
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j]))
                script.add("+" + b.get(j++));
            else script.add("-" + a.get(i++));
        }
        StringBuilder out = new StringBuilder("--- a/" + name + "\n+++ b/" + name + "\n");
        int context = 3;
        int k = 0;
        while (k < script.size()) {
            while (k < script.size() && script.get(k).charAt(0) == ' ') k++;
            if (k >= script.size()) break;
            int start = Math.max(0, k - context);
            int end = k;
            int lastChange = k;
            while (end < script.size() && end - lastChange <= 2 * context) {
                if (script.get(end).charAt(0) != ' ') lastChange = end;
                end++;
            }
            end = Math.min(script.size(), lastChange + context + 1);
            int oldStart = 1;
            int newStart = 1;
            for (int x = 0; x < start; x++) {
                char c = script.get(x).charAt(0);
                if (c != '+') oldStart++;
                if (c != '-') newStart++;
            }
            int oldLen = 0;
            int newLen = 0;
            StringBuilder hunk = new StringBuilder();
            for (int x = start; x < end; x++) {
                char c = script.get(x).charAt(0);
                if (c != '+') oldLen++;
                if (c != '-') newLen++;
                hunk.append(script.get(x)).append('\n');
            }
            out.append("@@ -")
                    .append(oldStart)
                    .append(',')
                    .append(oldLen)
                    .append(" +")
                    .append(newStart)
                    .append(',')
                    .append(newLen)
                    .append(" @@\n")
                    .append(hunk);
            k = end;
        }
        return out.toString();
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) return new ArrayList<>();
        List<String> l = new ArrayList<>(List.of(text.split("\\r?\\n", -1)));
        if (text.endsWith("\n")) l.remove(l.size() - 1);
        return l;
    }
}
