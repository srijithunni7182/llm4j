package io.github.llm4j.grounding;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.LLMProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs a grounded request on a provider that has no search of its own, using the client-side search
 * tool the {@link Grounding} carries ({@link Grounding.Builder#fallback}): the model is offered the
 * tool, its calls are run here, and the URLs in the tool's results become the citations, so one
 * agent definition can run on a provider with native grounding and on one without. Opt-in per
 * request, never implicit, because the two differ in cost, latency and trust.
 *
 * <p>The tool is run at most {@link Grounding#maxSearches()} times (3 by default); the model is
 * then asked to answer with what it has.
 */
public final class GroundingFallback {

    /** How many searches the model may run when {@link Grounding#maxSearches()} says nothing. */
    public static final int DEFAULT_MAX_SEARCHES = 3;

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')\\]]+");

    private GroundingFallback() {}

    /**
     * Answers {@code request} with the provider's own tool calling and the grounding's search tool.
     */
    public static LLMResponse run(LLMProvider provider, LLMRequest request) {
        Grounding grounding = request.getGrounding();
        Tool tool =
                grounding
                        .fallback()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "the grounding has no fallback tool"));
        if (!provider.supportsToolCalling()) {
            throw new InvalidRequestException(
                    "provider "
                            + provider.getProviderName()
                            + " does not support grounding and cannot call tools either, so the fallback tool "
                            + tool.getName()
                            + " cannot be offered to it; use a provider with grounding or with tool calling");
        }
        int limit =
                grounding.maxSearches() != null ? grounding.maxSearches() : DEFAULT_MAX_SEARCHES;
        List<ToolSpec> tools = new ArrayList<>(request.getTools());
        tools.add(new ToolSpec(tool.getName(), tool.getDescription(), tool.getParametersSchema()));
        List<Message> messages = new ArrayList<>(request.getMessages());
        Set<String> urls = new LinkedHashSet<>();
        List<String> queries = new ArrayList<>();
        int searches = 0;
        int prompt = 0;
        int completion = 0;
        boolean usage = false;
        LLMResponse last = null;

        for (int turn = 0; turn <= limit + 1; turn++) {
            boolean spent = searches >= limit;
            LLMRequest.Builder next =
                    request.toBuilder().grounding((Grounding) null).messages(messages);
            if (spent)
                next.tools(request.getTools()); // no more searching: answer with what there is
            else next.tools(tools);
            last = provider.chat(next.build());
            if (last.getTokenUsage() != null) {
                usage = true;
                prompt += last.getTokenUsage().getPromptTokens();
                completion += last.getTokenUsage().getCompletionTokens();
            }
            List<ToolCall> mine =
                    last.getToolCalls().stream()
                            .filter(c -> c.name().equals(tool.getName()))
                            .toList();
            boolean others = last.getToolCalls().size() > mine.size();
            if (mine.isEmpty() || others)
                break; // an answer, or a call to one of the caller's own tools: theirs to handle
            messages.add(
                    Message.assistantToolCalls(
                            last.getContent(), last.getToolCalls(), last.getProviderData()));
            for (ToolCall call : mine) {
                String result;
                if (searches >= limit) {
                    result = "Search limit reached; answer with the sources already found.";
                } else {
                    searches++;
                    Object q =
                            call.arguments().getOrDefault("query", call.arguments().get("input"));
                    if (q != null) queries.add(String.valueOf(q));
                    result = run(tool, call.arguments());
                    Matcher m = URL.matcher(result);
                    while (m.find()) urls.add(trim(m.group()));
                }
                messages.add(Message.toolResult(call.id(), call.name(), result));
            }
        }

        List<Citation> citations = new ArrayList<>();
        for (String url : urls)
            citations.add(new Citation(citations.size() + 1, url, null, null, List.of()));
        Map<String, Object> metadata = new HashMap<>(last.getMetadata());
        metadata.put("grounding.fallback", tool.getName());
        metadata.put("grounding.citation_basis", "consulted");
        LLMResponse.Builder b =
                last.toBuilder()
                        .citations(citations)
                        .searches(new SearchUsage(searches, queries, null))
                        .metadata(metadata);
        if (usage) b.tokenUsage(prompt, completion, prompt + completion);
        return b.build();
    }

    /** The answer as a stream: its text in one chunk, then the final chunk with everything else. */
    public static Stream<LLMResponse> asStream(LLMResponse whole) {
        LLMResponse text =
                LLMResponse.builder().content(whole.getContent()).model(whole.getModel()).build();
        LLMResponse.Builder last = whole.toBuilder().content("");
        return Stream.of(text, last.build());
    }

    private static String run(Tool tool, Map<String, Object> args) {
        try {
            String out = tool.execute(new LinkedHashMap<>(args));
            return out == null ? "" : out;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String trim(String url) {
        String u = url;
        while (!u.isEmpty() && ".,;:".indexOf(u.charAt(u.length() - 1)) >= 0)
            u = u.substring(0, u.length() - 1);
        return u;
    }
}
