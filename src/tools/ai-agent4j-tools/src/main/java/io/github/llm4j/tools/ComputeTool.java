package io.github.llm4j.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.tool.EffectContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code compute}: pure computation from its arguments (D13). {@code calc} (exact decimals), {@code
 * date} (on the run's clock), {@code json} (a value by path, or the keys) and {@code csv} (count,
 * sum, mean, min, max, by group). Every result is a JSON object; nothing is read or written outside
 * the arguments.
 */
final class ComputeTool extends GenericTool {

    static final int MAX_JSON_DEPTH = 64;
    static final int MAX_GROUPS = 1000;
    static final long MAX_DATE_AMOUNT = 1_000_000;

    private static final ObjectMapper OUT = new ObjectMapper();
    private static final ObjectMapper IN = reader();

    private final long maxBytes;
    private final int maxRows;
    private final EffectContext context;

    ComputeTool(String name, long maxBytes, int maxRows, EffectContext context) {
        super(
                name,
                "compute",
                "Computes exactly, from its arguments only. action: calc (expression, e.g."
                        + " \"(12.50 + 3) * 1.2\"; round, min, max, abs), date (op: today, add with"
                        + " date and days/months/years, between with date and to, weekday with date;"
                        + " dates as yyyy-MM-dd), json (document, path like a.b[0].c, op: get or"
                        + " keys), csv (text with a header row, column, op: count, sum, mean, min or"
                        + " max, optional group_by).",
                new Redactor(List.of()),
                context);
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
        this.context = context;
    }

    private static ObjectMapper reader() {
        ObjectMapper m =
                new ObjectMapper()
                        .enable(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .USE_BIG_DECIMAL_FOR_FLOATS);
        m.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_DEPTH).build());
        return m;
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws Exception {
        String action = optionalText(args, "action");
        if (action == null) throw new ToolRefusal("action is required: calc, date, json or csv");
        Map<String, Object> result =
                switch (action) {
                    case "calc" -> calc(args);
                    case "date" -> date(args);
                    case "json" -> json(args);
                    case "csv" -> csv(args);
                    default -> throw new ToolRefusal(
                            "action must be calc, date, json or csv, not " + action);
                };
        return OUT.writeValueAsString(result);
    }

    // ---- calc

    private static Map<String, Object> calc(Map<String, Object> args) {
        Expression.Result r = Expression.evaluate(text(args, "expression"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", r.value().toPlainString());
        out.put("rounded", r.rounded());
        return out;
    }

    // ---- date

    private Map<String, Object> date(Map<String, Object> args) {
        String op = optionalText(args, "op");
        if (op == null) op = "";
        Map<String, Object> out = new LinkedHashMap<>();
        switch (op) {
            case "today" -> out.put("value", LocalDate.now(context.clock()).toString());
            case "add" -> {
                LocalDate d = day(args, "date");
                d = d.plusYears(amount(args, "years"));
                d = d.plusMonths(amount(args, "months"));
                d = d.plusDays(amount(args, "days"));
                out.put("value", d.toString());
            }
            case "between" -> out.put(
                    "days", ChronoUnit.DAYS.between(day(args, "date"), day(args, "to")));
            case "weekday" -> out.put("value", day(args, "date").getDayOfWeek().toString());
            default -> throw new ToolRefusal(
                    "date: op must be today, add, between or weekday, not " + op);
        }
        return out;
    }

    private static LocalDate day(Map<String, Object> args, String key) {
        String v = optionalText(args, key);
        if (v == null) throw new ToolRefusal("date: " + key + " is required (yyyy-MM-dd)");
        try {
            return LocalDate.parse(v.strip());
        } catch (DateTimeParseException e) {
            throw new ToolRefusal("date: " + key + " must be a date written yyyy-MM-dd");
        }
    }

    private static long amount(Map<String, Object> args, String key) {
        String v = optionalText(args, key);
        if (v == null) return 0;
        long n;
        try {
            n = new BigDecimal(v.strip()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ToolRefusal("date: " + key + " must be a whole number");
        }
        if (Math.abs(n) > MAX_DATE_AMOUNT)
            throw new ToolRefusal(
                    "date: " + key + " must be at most " + MAX_DATE_AMOUNT + " either way");
        return n;
    }

    // ---- json

    private Map<String, Object> json(Map<String, Object> args) {
        String document = bounded(args, "document");
        String op = optionalText(args, "op");
        if (op == null) op = "get";
        if (!op.equals("get") && !op.equals("keys"))
            throw new ToolRefusal("json: op must be get or keys, not " + op);
        JsonNode root;
        try {
            root = IN.readTree(document);
        } catch (StreamConstraintsException e) {
            throw new ToolRefusal(
                    "json: the document is nested more than " + MAX_JSON_DEPTH + " deep");
        } catch (JsonProcessingException e) {
            throw new ToolRefusal("json: the document is not JSON");
        }
        String path = optionalText(args, "path");
        JsonNode node = path == null ? root : at(root, path);
        Map<String, Object> out = new LinkedHashMap<>();
        if (op.equals("keys")) {
            if (!node.isObject())
                throw new ToolRefusal("json: the value at " + path + " is not an object");
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            out.put("keys", keys);
        } else out.put("value", node);
        return out;
    }

    /** The value at {@code a.b[0].c}; a missing one is refused, naming the path. */
    private static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        int i = 0;
        boolean first = true;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) throw badPath(path);
                int index;
                try {
                    index = Integer.parseInt(path.substring(i + 1, close));
                } catch (NumberFormatException e) {
                    throw badPath(path);
                }
                node = node.isArray() ? node.get(index) : null;
                i = close + 1;
            } else {
                if (c == '.') {
                    if (first) throw badPath(path);
                    i++;
                }
                int end = i;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[')
                    end++;
                if (end == i) throw badPath(path);
                node = node.isObject() ? node.get(path.substring(i, end)) : null;
                i = end;
            }
            first = false;
            if (node == null || node.isMissingNode())
                throw new ToolRefusal("json: no value at " + path);
        }
        return node;
    }

    private static ToolRefusal badPath(String path) {
        return new ToolRefusal("json: the path " + path + " is not of the form a.b[0].c");
    }

    // ---- csv

    private Map<String, Object> csv(Map<String, Object> args) {
        String text = bounded(args, "text");
        String column = text(args, "column");
        String op = optionalText(args, "op");
        if (op == null || !List.of("count", "sum", "mean", "min", "max").contains(op))
            throw new ToolRefusal("csv: op must be count, sum, mean, min or max, not " + op);
        String groupBy = optionalText(args, "group_by");
        List<List<String>> rows = Csv.parse(text, maxRows);
        if (rows.isEmpty()) throw new ToolRefusal("csv: there is no header row");
        List<String> header = rows.get(0);
        int col = columnOf(header, column);
        int group = groupBy == null ? -1 : columnOf(header, groupBy);
        Map<String, Stat> stats = new LinkedHashMap<>();
        Stat all = new Stat();
        boolean truncated = false;
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String cell = col < row.size() ? row.get(col).strip() : "";
            Stat target = all;
            if (group >= 0) {
                String key = group < row.size() ? row.get(group) : "";
                target = stats.get(key);
                if (target == null) {
                    if (stats.size() >= MAX_GROUPS) {
                        truncated = true;
                        continue;
                    }
                    target = new Stat();
                    stats.put(key, target);
                }
            }
            target.rows++;
            if (cell.isEmpty()) {
                target.skipped++;
                continue;
            }
            if (op.equals("count")) {
                target.count++;
                continue;
            }
            BigDecimal v;
            try {
                v = new BigDecimal(cell);
            } catch (NumberFormatException e) {
                throw new ToolRefusal("csv: row " + r + ", column " + column + " is not a number");
            }
            target.add(v);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (group < 0) {
            out.put("value", all.result(op));
            out.put("rows", all.rows);
            out.put("skipped", all.skipped);
        } else {
            Map<String, Object> groups = new LinkedHashMap<>();
            stats.forEach((k, s) -> groups.put(k, s.result(op)));
            out.put("groups", groups);
            out.put("rows", rows.size() - 1);
            out.put("truncated", truncated);
        }
        return out;
    }

    private static int columnOf(List<String> header, String name) {
        int i = header.indexOf(name);
        if (i < 0)
            throw new ToolRefusal(
                    "csv: there is no column "
                            + name
                            + "; the columns are "
                            + String.join(", ", header));
        return i;
    }

    /** Count, sum, min and max of one group's numbers. */
    private static final class Stat {
        int rows;
        int skipped;
        int count;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min;
        BigDecimal max;

        void add(BigDecimal v) {
            count++;
            sum = sum.add(v);
            if (min == null || v.compareTo(min) < 0) min = v;
            if (max == null || v.compareTo(max) > 0) max = v;
        }

        String result(String op) {
            return switch (op) {
                case "count" -> String.valueOf(count);
                case "sum" -> sum.toPlainString();
                case "mean" -> count == 0
                        ? ""
                        : sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128)
                                .stripTrailingZeros()
                                .toPlainString();
                case "min" -> min == null ? "" : min.toPlainString();
                default -> max == null ? "" : max.toPlainString();
            };
        }
    }

    private String bounded(Map<String, Object> args, String key) {
        String v = args.get(key) instanceof String s ? s : optionalText(args, key);
        if (v == null) throw new ToolRefusal(key + " is required");
        if (v.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new ToolRefusal(key + " is larger than " + maxBytes + " bytes (max_bytes)");
        return v;
    }
}
