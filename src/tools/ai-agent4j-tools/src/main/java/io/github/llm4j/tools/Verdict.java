package io.github.llm4j.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The record {@code verify} returns (D12): counts and a status the host parsed, and the items
 * (name, status, message) whose text came from the report and so may have been written by a model.
 * It says whether an artifact passed a check, never whether a requirement was met.
 *
 * <p>{@code failed} counts every item that neither passed nor was skipped, and a report that cannot
 * be read counts as one failure, so a gate on {@code failed > 0} never passes what it could not
 * read.
 */
final class Verdict {

    /** Longest message kept for an item. */
    static final int MAX_MESSAGE = 500;

    private final String format;
    private final String source;
    private final int maxItems;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private int total;
    private int passed;
    private int failed;
    private int errors;
    private int skipped;
    private boolean truncated;
    private String error;

    Verdict(String format, String source, int maxItems) {
        this.format = format;
        this.source = source;
        this.maxItems = maxItems;
    }

    /** A report or document that could not be read: one failure, and why. */
    static Verdict unreadable(String format, String source, int maxItems, String why) {
        Verdict v = new Verdict(format, source, maxItems);
        v.failed = 1;
        v.error = why;
        return v;
    }

    void passed(String name) {
        total++;
        passed++;
        item(name, "passed", null);
    }

    void failed(String name, String message) {
        total++;
        failed++;
        item(name, "failed", message);
    }

    void error(String name, String message) {
        total++;
        failed++;
        errors++;
        item(name, "error", message);
    }

    void skipped(String name) {
        total++;
        skipped++;
        item(name, "skipped", null);
    }

    /** A violation of a schema: the document fails, item by item. */
    void violation(String where, String message) {
        failed++;
        item(where, "failed", message);
    }

    /** For a schema: one document checked. */
    void checkedOne() {
        total = 1;
        if (failed == 0) passed = 1;
    }

    private void item(String name, String status, String message) {
        if (items.size() >= maxItems) {
            truncated = true;
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("status", status);
        if (message != null) item.put("message", cut(message));
        items.add(item);
    }

    private static String cut(String text) {
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE) + "…";
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", format);
        if (source != null) out.put("source", source);
        out.put("status", error != null || failed > 0 ? "failed" : total == 0 ? "empty" : "passed");
        out.put("total", total);
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("errors", errors);
        out.put("skipped", skipped);
        out.put("items", items);
        out.put("items_truncated", truncated);
        if (error != null) out.put("error", cut(error));
        return out;
    }
}
