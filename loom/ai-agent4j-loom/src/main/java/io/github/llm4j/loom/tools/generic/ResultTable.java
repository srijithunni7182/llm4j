package io.github.llm4j.loom.tools.generic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders a query result as a text table, JSON or CSV, with bounded rows and cell sizes. */
final class ResultTable {

    private static final int MAX_CELL = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> columns = new ArrayList<>();
    private final List<List<Object>> rows = new ArrayList<>();
    private boolean moreRows;

    /** Reads at most {@code maxRows} rows; remembers whether the result had more. */
    static ResultTable read(ResultSet rs, int maxRows) throws SQLException {
        ResultTable t = new ResultTable();
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        for (int i = 1; i <= count; i++) t.columns.add(meta.getColumnLabel(i));
        while (rs.next()) {
            if (t.rows.size() == maxRows) {
                t.moreRows = true;
                break;
            }
            List<Object> row = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) row.add(cell(rs.getObject(i)));
            t.rows.add(row);
        }
        return t;
    }

    private static Object cell(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] b) return "<binary " + b.length + " bytes>";
        if (value instanceof Number || value instanceof Boolean) return value;
        String s = String.valueOf(value);
        return s.length() > MAX_CELL ? s.substring(0, MAX_CELL) + "…" : s;
    }

    String render(String format) {
        String body = switch (format) {
            case "json" -> json();
            case "csv" -> csv();
            default -> table();
        };
        return moreRows ? body + "\n(only the first " + rows.size() + " rows are shown; there are more)" : body;
    }

    private String table() {
        if (rows.isEmpty()) return String.join(" | ", columns) + "\n(no rows)";
        StringBuilder sb = new StringBuilder(String.join(" | ", columns)).append('\n');
        sb.append("-".repeat(Math.max(3, String.join(" | ", columns).length()))).append('\n');
        for (List<Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (Object v : row) cells.add(v == null ? "NULL" : String.valueOf(v).replace("\r", " ").replace("\n", "\\n"));
            sb.append(String.join(" | ", cells)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String json() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) m.put(columns.get(i), row.get(i));
            out.add(m);
        }
        try {
            return JSON.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new ToolRefusal("the result can't be written as JSON");
        }
    }

    private String csv() {
        StringBuilder sb = new StringBuilder(String.join(",", columns.stream().map(ResultTable::csvCell).toList())).append('\n');
        for (List<Object> row : rows) {
            sb.append(String.join(",", row.stream().map(v -> v == null ? "" : csvCell(String.valueOf(v))).toList())).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String csvCell(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
