package io.github.llm4j.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolCallDelta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Reads a streamed model call to its end and puts it back together as the one {@link LLMResponse} a
 * whole call would have returned: the answer text, the tool calls (arguments joined and parsed),
 * and the finish reason, usage and provider data of the last chunk. While it reads, it tells the
 * caller about every piece, and it watches the {@link RunHandle}: a steer or cancel closes the
 * stream at once, even if the model is silent, and what had arrived is returned marked {@link
 * #INTERRUPTED} with no tool calls (half a call is never run).
 */
final class StreamedReply {

    /** Metadata key, on a response whose stream was cut short by the run handle. */
    static final String INTERRUPTED = "llm4j.interrupted";

    /**
     * Metadata key: the names of tool calls whose arguments were not valid JSON (cut off, or
     * malformed); those calls are left out.
     */
    static final String MALFORMED = "llm4j.malformedToolCalls";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the caller wants to hear while the call is read. */
    interface Sink {
        void reasoning(String piece);

        void text(String piece);

        void toolCallStarted(String name);
    }

    private StreamedReply() {}

    static LLMResponse read(Stream<LLMResponse> stream, RunHandle handle, Sink sink) {
        AtomicBoolean aborted = new AtomicBoolean();
        StringBuilder text = new StringBuilder();
        Map<Integer, Call> calls = new TreeMap<>();
        LLMResponse last = null;
        if (handle != null) {
            handle.onInterrupt(
                    () -> {
                        aborted.set(true);
                        stream.close();
                    });
        }
        try {
            Iterator<LLMResponse> chunks = stream.iterator();
            while (!aborted.get() && chunks.hasNext()) {
                LLMResponse chunk = chunks.next();
                last = chunk;
                if (chunk.getReasoning() != null && !chunk.getReasoning().isEmpty())
                    sink.reasoning(chunk.getReasoning());
                if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                    text.append(chunk.getContent());
                    sink.text(chunk.getContent());
                }
                ToolCallDelta d = chunk.getToolCallDelta();
                if (d != null) {
                    Call call = calls.computeIfAbsent(d.index(), k -> new Call());
                    if (d.id() != null) call.id = d.id();
                    if (d.name() != null && call.name == null) {
                        call.name = d.name();
                        sink.toolCallStarted(d.name());
                    }
                    call.arguments.append(d.argumentsFragment());
                }
            }
        } catch (RuntimeException e) {
            // closing the stream under a blocked read is how an interrupt gets the reader out;
            // anything else is a real failure
            if (!aborted.get()) throw e;
        } finally {
            if (handle != null) handle.onInterrupt(null);
            stream.close();
        }

        LLMResponse.Builder out = LLMResponse.builder().content(text.toString());
        Map<String, Object> metadata = new HashMap<>();
        if (last != null) {
            out.model(last.getModel());
            if (last.getMetadata() != null) metadata.putAll(last.getMetadata());
        }
        if (aborted.get()) {
            metadata.put(INTERRUPTED, Boolean.TRUE);
            return out.metadata(metadata).build();
        }
        List<ToolCall> parsed = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        for (Call call : calls.values()) {
            if (call.name == null) continue;
            try {
                String json =
                        call.arguments.toString().isBlank() ? "{}" : call.arguments.toString();
                Map<String, Object> args =
                        JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
                parsed.add(new ToolCall(call.id, call.name, args));
            } catch (Exception e) {
                malformed.add(call.name);
            }
        }
        if (!malformed.isEmpty()) metadata.put(MALFORMED, List.copyOf(malformed));
        if (last != null) {
            out.tokenUsage(last.getTokenUsage()).providerData(last.getProviderData());
            out.finishReason(last.getFinishReason());
            out.citations(last.getCitations()).searches(last.getSearches());
        }
        return out.toolCalls(parsed).metadata(metadata).build();
    }

    private static final class Call {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
