package io.github.llm4j.provider.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.grounding.Span;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.provider.Providers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Responses-schema response object (whole, or the final one of a stream) for a {@link
 * ResponsesDialect}.
 */
final class ResponsesReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponsesReader() {}

    static JsonNode parse(ResponsesDialect dialect, String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject())
                throw new ProviderException(dialect.name(), "Response is not a JSON object");
            return root;
        } catch (IOException e) {
            throw new ProviderException(dialect.name(), "Response is not JSON", e);
        }
    }

    static LLMResponse read(ResponsesDialect dialect, JsonNode root, String requestedModel) {
        return read(dialect, root, requestedModel, false);
    }

    /**
     * Reads the response; when {@code grounded}, also its sources and search usage as the dialect's
     * {@link ResponsesGrounding} says where to find them.
     */
    static LLMResponse read(
            ResponsesDialect dialect, JsonNode root, String requestedModel, boolean grounded) {
        failOnError(dialect, root);
        String status = root.path("status").asText(null);
        if ("failed".equals(status)) {
            throw new ProviderException(
                    dialect.name(),
                    root.path("error").path("message").asText("The response failed"));
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        Map<String, Source> sources = new LinkedHashMap<>();
        boolean annotated = grounded && isCited(dialect);
        for (JsonNode item : root.path("output")) {
            switch (item.path("type").asText()) {
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asText())) {
                            String piece = part.path("text").asText("");
                            if (annotated)
                                cite(part.path("annotations"), piece, text.length(), sources);
                            text.append(piece);
                        } else if ("refusal".equals(part.path("type").asText())) {
                            throw new ContentBlockedException(
                                    dialect.name(), "refused: " + part.path("refusal").asText());
                        }
                    }
                }
                case "function_call" -> {
                    String name = item.path("name").asText();
                    calls.add(
                            new ToolCall(
                                    item.hasNonNull("call_id")
                                            ? item.get("call_id").asText()
                                            : item.path("id").asText(null),
                                    name,
                                    arguments(dialect, name, item.path("arguments").asText("{}"))));
                }
                default -> {
                    // reasoning, search results and other items are not part of the answer
                }
            }
        }
        String reason = root.path("incomplete_details").path("reason").asText(null);
        String raw = "incomplete".equals(status) && reason != null ? reason : status;
        LLMResponse.Builder b =
                LLMResponse.builder()
                        .content(text.toString())
                        .model(
                                root.hasNonNull("model")
                                        ? root.get("model").asText()
                                        : requestedModel)
                        .toolCalls(calls)
                        .finishReason(finish(status, reason, !calls.isEmpty()))
                        .addMetadata(Providers.FINISH_REASON_RAW, raw);
        if (root.hasNonNull("id")) b.addMetadata("response_id", root.get("id").asText());
        if (!calls.isEmpty()) {
            b.providerData(
                    Map.of(
                            ResponsesRequestWriter.OUTPUT,
                            JSON.convertValue(
                                    root.path("output"),
                                    new TypeReference<List<Map<String, Object>>>() {})));
        }
        usage(root.path("usage"), b);
        if (grounded && dialect.grounding() != null) {
            if (!annotated) consulted(dialect.grounding(), root, sources);
            groundingOf(dialect.grounding(), root, sources, text.toString(), b);
        }
        return b.build();
    }

    private static boolean isCited(ResponsesDialect dialect) {
        return dialect.grounding() != null
                && dialect.grounding().sources() == ResponsesGrounding.Sources.ANNOTATIONS;
    }

    /** A source being collected: its title and the places in the answer that cite it. */
    private static final class Source {
        String title;
        String snippet;
        final List<Span> spans = new ArrayList<>();
    }

    /**
     * Collects the {@code url_citation} annotations of one answer part. Their indices count Unicode
     * code points within the part; they become Java string offsets within the whole answer ({@code
     * base} is where the part starts), and a range outside the part is dropped.
     */
    private static void cite(
            JsonNode annotations, String part, int base, Map<String, Source> into) {
        int points = part.codePointCount(0, part.length());
        for (JsonNode a : annotations) {
            if (!"url_citation".equals(a.path("type").asText()) || !a.hasNonNull("url")) continue;
            Source s = into.computeIfAbsent(a.get("url").asText(), k -> new Source());
            String title = a.path("title").asText(null);
            // some vendors give only the citation number as the title: no information
            if (s.title == null && title != null && !title.isBlank() && !title.matches("\\d+"))
                s.title = title;
            int from = a.path("start_index").asInt(-1);
            int to = a.path("end_index").asInt(-1);
            if (from >= 0 && to >= from && to <= points) {
                s.spans.add(
                        new Span(
                                base + part.offsetByCodePoints(0, from),
                                base + part.offsetByCodePoints(0, to)));
            }
        }
    }

    /** Collects the results of the vendor's results item: what it consulted. */
    private static void consulted(ResponsesGrounding g, JsonNode root, Map<String, Source> into) {
        for (JsonNode item : root.path("output")) {
            if (!item.path("type").asText().equals(g.resultsType())) continue;
            for (JsonNode result : item.path("results")) {
                if (!result.hasNonNull("url")) continue;
                Source s = into.computeIfAbsent(result.get("url").asText(), k -> new Source());
                if (s.title == null && result.hasNonNull("title"))
                    s.title = result.get("title").asText();
                if (s.snippet == null && result.hasNonNull("snippet"))
                    s.snippet = result.get("snippet").asText();
            }
        }
    }

    /**
     * Sets the citations, the search usage and the basis of the citations from what the dialect
     * says: the count is what the vendor reports (a field, else the search items it listed), and
     * stays unknown when the response says nothing.
     */
    private static void groundingOf(
            ResponsesGrounding g,
            JsonNode root,
            Map<String, Source> sources,
            String answer,
            LLMResponse.Builder b) {
        List<Citation> citations = new ArrayList<>();
        int index = 1;
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            Source s = e.getValue();
            citations.add(
                    new Citation(index++, e.getKey(), s.title, s.snippet, s.spans)
                            .withSpansWithin(answer));
        }
        b.citations(citations);
        if (!citations.isEmpty()) b.addMetadata("grounding.citation_basis", g.sources().basis());

        Integer count = null;
        if (g.countPath() != null) {
            JsonNode n = at(root, g.countPath());
            if (n.isNumber()) count = n.asInt();
        }
        Set<String> queries = new LinkedHashSet<>();
        int calls = 0;
        for (JsonNode item : root.path("output")) {
            String type = item.path("type").asText();
            if (type.equals(g.callType())
                    && (!item.hasNonNull("status")
                            || "completed".equals(item.get("status").asText()))) {
                calls++;
            }
            if (type.equals(g.callType()) || type.equals(g.resultsType())) {
                JsonNode action = item.path("action");
                for (JsonNode q : item.path("queries")) queries.add(q.asText());
                for (JsonNode q : action.path("queries")) queries.add(q.asText());
                if (action.hasNonNull("query")) queries.add(action.get("query").asText());
            }
        }
        if (count == null && g.callType() != null) count = calls;
        // a response with usage and no word of any search ran none
        if (count == null && root.path("usage").isObject()) count = 0;
        Double cost = null;
        if (g.costPath() != null && at(root, g.costPath()).isNumber())
            cost = at(root, g.costPath()).asDouble();
        b.searches(new SearchUsage(count, new ArrayList<>(queries), cost));
    }

    private static JsonNode at(JsonNode root, String dotted) {
        JsonNode n = root;
        for (String part : dotted.split("\\.")) n = n.path(part);
        return n;
    }

    /**
     * The normalised finish reason ({@code stop}, {@code length}, …), or null when the status is
     * not one we know.
     */
    static String finish(String status, String incompleteReason, boolean calls) {
        if (calls) return "tool_calls";
        if ("completed".equals(status)) return "stop";
        if ("incomplete".equals(status)) {
            if ("content_filter".equals(incompleteReason)) return "content_filter";
            return "length";
        }
        return null;
    }

    static void failOnError(ResponsesDialect dialect, JsonNode root) {
        JsonNode error = root.get("error");
        if (error == null || error.isNull()) return;
        String message =
                error.isTextual() ? error.asText() : error.path("message").asText(error.toString());
        throw new ProviderException(dialect.name(), message);
    }

    static Map<String, Object> arguments(ResponsesDialect dialect, String tool, String json) {
        try {
            JsonNode node = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!node.isObject()) throw new IOException("not an object");
            return JSON.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new ProviderException(
                    dialect.name(),
                    "The arguments of tool call " + tool + " are not a JSON object",
                    e);
        }
    }

    static void usage(JsonNode usage, LLMResponse.Builder b) {
        if (!usage.isObject()) return;
        int in = usage.path("input_tokens").asInt(0);
        int out = usage.path("output_tokens").asInt(0);
        b.tokenUsage(in, out, usage.path("total_tokens").asInt(in + out));
        JsonNode cached = usage.path("input_tokens_details").path("cached_tokens");
        if (cached.isNumber()) b.addMetadata("usage.cached_tokens", cached.asInt());
        JsonNode reasoning = usage.path("output_tokens_details").path("reasoning_tokens");
        if (reasoning.isNumber()) b.addMetadata("usage.reasoning_tokens", reasoning.asInt());
    }
}
