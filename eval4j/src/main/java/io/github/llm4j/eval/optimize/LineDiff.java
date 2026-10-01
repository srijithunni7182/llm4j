package io.github.llm4j.eval.optimize;

import java.util.ArrayList;
import java.util.List;

/** A small line-based unified diff (LCS), enough for reviewing prompt changes. */
final class LineDiff {

    private static final int CONTEXT = 3;

    private LineDiff() {}

    /** Returns a unified diff for one file, or an empty string when the texts are equal. */
    static String unified(String path, String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<String> ops = script(a, b);
        if (ops.stream().allMatch(op -> op.charAt(0) == ' ')) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(path).append('\n');
        out.append("+++ b/").append(path).append('\n');
        int i = 0;
        int aLine = 1;
        int bLine = 1;
        int[] aAt = new int[ops.size() + 1];
        int[] bAt = new int[ops.size() + 1];
        for (int k = 0; k < ops.size(); k++) {
            aAt[k] = aLine;
            bAt[k] = bLine;
            char c = ops.get(k).charAt(0);
            if (c != '+') {
                aLine++;
            }
            if (c != '-') {
                bLine++;
            }
        }
        while (i < ops.size()) {
            if (ops.get(i).charAt(0) == ' ') {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            int lastChange = i;
            while (end < ops.size() && end - lastChange <= 2 * CONTEXT) {
                if (ops.get(end).charAt(0) != ' ') {
                    lastChange = end;
                }
                end++;
            }
            end = Math.min(ops.size(), lastChange + 1 + CONTEXT);
            int aCount = 0;
            int bCount = 0;
            for (int k = start; k < end; k++) {
                char c = ops.get(k).charAt(0);
                if (c != '+') {
                    aCount++;
                }
                if (c != '-') {
                    bCount++;
                }
            }
            out.append("@@ -")
                    .append(aCount == 0 ? aAt[start] - 1 : aAt[start])
                    .append(',')
                    .append(aCount)
                    .append(" +")
                    .append(bCount == 0 ? bAt[start] - 1 : bAt[start])
                    .append(',')
                    .append(bCount)
                    .append(" @@\n");
            for (int k = start; k < end; k++) {
                out.append(ops.get(k)).append('\n');
            }
            i = end;
        }
        return out.toString();
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // a trailing newline is not an extra empty line
        }
        return lines;
    }

    /** Edit script of " line", "-line" and "+line" entries. */
    private static List<String> script(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] =
                        a.get(i).equals(b.get(j))
                                ? lcs[i + 1][j + 1] + 1
                                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(" " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add("-" + a.get(i++));
            } else {
                ops.add("+" + b.get(j++));
            }
        }
        while (i < n) {
            ops.add("-" + a.get(i++));
        }
        while (j < m) {
            ops.add("+" + b.get(j++));
        }
        return ops;
    }
}
