package io.github.llm4j.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.grounding.GroundingOption;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.grounding.Span;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Web-search grounding for Claude: the server tool {@code web_search_20250305} in the request, and
 * the sources and search count read from the content blocks of the answer. Checked against a
 * grounded answer, a grounded stream and an allowed-domain answer recorded from the real API
 * (2026-10-22); blocked domains, the country and the round trip of a tool turn are still written
 * from Anthropic's reference.
 *
 * <p>Claude cites by text block: a {@code text} block carries {@code citations} for the sentence it
 * holds, so a citation's span is that block's range in the answer. Searches are counted as the
 * usage says ({@code server_tool_use.web_search_requests}), else as the {@code server_tool_use}
 * blocks the answer lists.
 */
final class AnthropicGrounding {

    static final Set<GroundingOption> OPTIONS =
            Set.of(
                    GroundingOption.MAX_SEARCHES,
                    GroundingOption.ALLOW_DOMAINS,
                    GroundingOption.BLOCK_DOMAINS,
                    GroundingOption.COUNTRY);

    private AnthropicGrounding() {}

    /** Adds the search tool to the request's {@code tools}. */
    static void addTool(ArrayNode tools, Grounding grounding) {
        ObjectNode tool =
                tools.addObject().put("type", "web_search_20250305").put("name", "web_search");
        if (grounding.maxSearches() != null) tool.put("max_uses", grounding.maxSearches());
        if (!grounding.allowDomains().isEmpty())
            grounding.allowDomains().forEach(tool.putArray("allowed_domains")::add);
        if (!grounding.blockDomains().isEmpty())
            grounding.blockDomains().forEach(tool.putArray("blocked_domains")::add);
        if (grounding.country() != null) {
            tool.putObject("user_location")
                    .put("type", "approximate")
                    .put("country", grounding.country());
        }
    }

    /** A source being collected. */
    private static final class Source {
        String title;
        String snippet;
        final List<Span> spans = new ArrayList<>();
    }

    /**
     * Sets the citations, the search usage and the citation basis from the answer's content blocks
     * and usage.
     */
    static void read(JsonNode content, JsonNode usage, LLMResponse.Builder b) {
        Map<String, Source> cited = new LinkedHashMap<>();
        Map<String, Source> consulted = new LinkedHashMap<>();
        Set<String> queries = new LinkedHashSet<>();
        int searchBlocks = 0;
        int at = 0;
        for (JsonNode block : content) {
            switch (block.path("type").asText()) {
                case "text" -> {
                    String text = block.path("text").asText("");
                    int end = at + text.length();
                    for (JsonNode c : block.path("citations")) {
                        if (!c.hasNonNull("url")) continue;
                        Source s = cited.computeIfAbsent(c.get("url").asText(), k -> new Source());
                        if (s.title == null && c.hasNonNull("title"))
                            s.title = c.get("title").asText();
                        if (s.snippet == null && c.hasNonNull("cited_text"))
                            s.snippet = c.get("cited_text").asText();
                        Span span = end > at ? new Span(at, end) : null;
                        if (span != null && !s.spans.contains(span)) s.spans.add(span);
                    }
                    at = end;
                }
                case "server_tool_use" -> {
                    if ("web_search".equals(block.path("name").asText())) {
                        searchBlocks++;
                        if (block.path("input").hasNonNull("query"))
                            queries.add(block.path("input").get("query").asText());
                    }
                }
                case "web_search_tool_result" -> {
                    JsonNode results = block.path("content");
                    if (results.isArray()) {
                        for (JsonNode r : results) {
                            if (!r.hasNonNull("url")) continue;
                            Source s =
                                    consulted.computeIfAbsent(
                                            r.get("url").asText(), k -> new Source());
                            if (s.title == null && r.hasNonNull("title"))
                                s.title = r.get("title").asText();
                        }
                    }
                }
                default -> {
                    // thinking, tool_use and others are not sources
                }
            }
        }
        boolean isCited = !cited.isEmpty();
        Map<String, Source> sources = isCited ? cited : consulted;
        List<Citation> citations = new ArrayList<>();
        int index = 1;
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            Source s = e.getValue();
            citations.add(new Citation(index++, e.getKey(), s.title, s.snippet, s.spans));
        }
        b.citations(citations);
        if (!citations.isEmpty())
            b.addMetadata("grounding.citation_basis", isCited ? "cited" : "consulted");

        JsonNode reported = usage.path("server_tool_use").path("web_search_requests");
        Integer count = reported.isNumber() ? reported.asInt() : searchBlocks;
        b.searches(new SearchUsage(count, new ArrayList<>(queries), null));
    }
}
