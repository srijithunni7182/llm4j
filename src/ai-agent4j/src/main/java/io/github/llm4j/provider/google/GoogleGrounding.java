package io.github.llm4j.provider.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.grounding.GroundingOption;
import io.github.llm4j.grounding.Recency;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.grounding.Span;
import io.github.llm4j.model.LLMResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grounding with Google Search for Gemini: the {@code googleSearch} tool in the request and the
 * sources read from the candidate's {@code groundingMetadata}. Checked against a grounded answer
 * and stream recorded from the real API (2026-10-22), and the request with a recency interval was
 * accepted by it.
 *
 * <p>Gemini's supports give segment positions as <b>UTF-8 byte offsets</b> into the answer; they
 * become Java string offsets here, and a segment that does not fit the answer is dropped. The
 * number of searches is the number of queries Gemini lists in {@code webSearchQueries}.
 */
final class GoogleGrounding {

    static final Set<GroundingOption> OPTIONS = Set.of(GroundingOption.RECENCY);

    private GoogleGrounding() {}

    private static Duration within(Recency recency) {
        return switch (recency) {
            case DAY -> Duration.ofDays(1);
            case WEEK -> Duration.ofDays(7);
            case MONTH -> Duration.ofDays(30);
            case YEAR -> Duration.ofDays(365);
        };
    }

    /** Adds the search tool; a recency becomes an interval that ends {@code now}. */
    static void addTool(ArrayNode tools, Grounding grounding, Instant now) {
        ObjectNode search = tools.addObject().putObject("googleSearch");
        if (grounding.recency() != null) {
            search.putObject("timeRangeFilter")
                    .put("startTime", now.minus(within(grounding.recency())).toString())
                    .put("endTime", now.toString());
        }
    }

    /** A source being collected. */
    private static final class Source {
        String title;
        final List<Span> spans = new ArrayList<>();
    }

    /** Sets the citations and search usage from a candidate and the answer text it produced. */
    static void read(JsonNode candidate, String answer, LLMResponse.Builder b) {
        JsonNode meta = candidate.path("groundingMetadata");
        List<String> queries = new ArrayList<>();
        meta.path("webSearchQueries").forEach(q -> queries.add(q.asText()));

        // chunk index -> source (a page listed twice is one source)
        Map<String, Source> byUrl = new LinkedHashMap<>();
        Map<Integer, Source> byChunk = new LinkedHashMap<>();
        int i = 0;
        for (JsonNode chunk : meta.path("groundingChunks")) {
            JsonNode web = chunk.path("web");
            if (web.hasNonNull("uri")) {
                Source s = byUrl.computeIfAbsent(web.get("uri").asText(), k -> new Source());
                if (s.title == null && web.hasNonNull("title")) s.title = web.get("title").asText();
                byChunk.put(i, s);
            }
            i++;
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        for (JsonNode support : meta.path("groundingSupports")) {
            JsonNode segment = support.path("segment");
            int from = chars(answer, bytes, segment.path("startIndex").asInt(0));
            int to = chars(answer, bytes, segment.path("endIndex").asInt(-1));
            if (from < 0 || to < from) continue;
            for (JsonNode index : support.path("groundingChunkIndices")) {
                Source s = byChunk.get(index.asInt(-1));
                Span span = new Span(from, to);
                if (s != null && !s.spans.contains(span)) s.spans.add(span);
            }
        }
        List<Citation> citations = new ArrayList<>();
        int n = 1;
        for (Map.Entry<String, Source> e : byUrl.entrySet()) {
            Source s = e.getValue();
            citations.add(new Citation(n++, e.getKey(), s.title, null, s.spans));
        }
        b.citations(citations);
        if (!citations.isEmpty()) b.addMetadata("grounding.citation_basis", "cited");
        b.searches(new SearchUsage(queries.size(), queries, null));
    }

    /**
     * The Java string offset of a UTF-8 byte offset into {@code answer}, or -1 when it is out of
     * range or falls inside a character.
     */
    static int chars(String answer, byte[] bytes, int byteOffset) {
        if (byteOffset < 0 || byteOffset > bytes.length) return -1;
        if (byteOffset < bytes.length && (bytes[byteOffset] & 0xC0) == 0x80) return -1;
        return new String(bytes, 0, byteOffset, StandardCharsets.UTF_8).length();
    }
}
