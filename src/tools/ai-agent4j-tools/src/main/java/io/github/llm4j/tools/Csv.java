package io.github.llm4j.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Comma-separated values as RFC 4180 writes them: fields separated by commas, a field in double
 * quotes may hold commas, line breaks and doubled quotes, and a line ends with LF or CRLF. Bounded
 * by a row cap; a quote that is never closed is refused.
 */
final class Csv {

    private Csv() {}

    /** The rows, the header first; more than {@code maxRows} rows after the header is refused. */
    static List<List<String>> parse(String text, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else quoted = false;
                } else field.append(c);
            } else if (c == '"' && field.length() == 0) {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (any) add(rows, row, maxRows); // a line with nothing on it is not a row
                row = new ArrayList<>();
                any = false;
            } else {
                field.append(c);
                any = true;
            }
            i++;
        }
        if (quoted) throw new ToolRefusal("csv: a quote is not closed");
        if (any || field.length() > 0) {
            row.add(field.toString());
            add(rows, row, maxRows);
        }
        return rows;
    }

    private static void add(List<List<String>> rows, List<String> row, int maxRows) {
        if (rows.size() > maxRows)
            throw new ToolRefusal("csv: the text has more than " + maxRows + " rows (max_rows)");
        rows.add(row);
    }
}
