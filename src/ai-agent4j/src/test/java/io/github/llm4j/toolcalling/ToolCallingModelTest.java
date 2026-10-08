package io.github.llm4j.toolcalling;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.privacy.MaskingLLMClient;
import io.github.llm4j.privacy.RegexPIIDetector;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.routing.ProviderTier;
import io.github.llm4j.routing.RoutingLLMClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TCM-*: the model types, the capability flag and the wrappers that carry tool calls through. */
class ToolCallingModelTest {

    @Test
    void tcm01_toolSpecNamesAreFunctionLegal() {
        assertTrue(ToolSpec.isLegalName("web_search-2"));
        assertTrue(ToolSpec.isLegalName("a".repeat(64)));
        for (String bad : new String[] {"", "has space", "dot.name", "a".repeat(65), "ünï", null}) assertFalse(ToolSpec.isLegalName(bad), String.valueOf(bad));
        assertThrows(IllegalArgumentException.class, () -> new ToolSpec("bad name", "d", null));
        ToolSpec spec = new ToolSpec("t", null, null);
        assertEquals("", spec.description());
        assertEquals("object", spec.parameters().get("type"), "no schema means any object");
    }

    @Test
    void tcm02_toolCallArgumentsAreImmutableAndIdsCanBeAdded() {
        Map<String, Object> mutable = new java.util.HashMap<>(Map.of("a", 1));
        ToolCall c = new ToolCall(null, "t", mutable);
        mutable.put("b", 2);
        assertEquals(Map.of("a", 1), c.arguments(), "a copy");
        assertThrows(UnsupportedOperationException.class, () -> c.arguments().put("x", 1));
        assertEquals("call_1", c.withId("call_1").id());
        assertNull(c.id());
        assertEquals(Map.of(), new ToolCall("i", "t", null).arguments());
    }

    @Test
    void tcm03_messagesCarryToolCallsAndResults() {
        ToolCall c = new ToolCall("c1", "calc", Map.of("e", "1+1"));
        Message ask = Message.assistantToolCalls(null, List.of(c), Map.of("raw", List.of(1)));
        assertEquals(Message.Role.ASSISTANT, ask.getRole());
        assertEquals("", ask.getContent(), "no text is an empty string, not null");
        assertEquals(List.of(c), ask.getToolCalls());
        assertEquals(Map.of("raw", List.of(1)), ask.getProviderData());

        Message result = Message.toolResult("c1", "calc", "2");
        assertEquals(Message.Role.TOOL, result.getRole());
        assertEquals("c1", result.getToolCallId());
        assertEquals("calc", result.getName());

        assertEquals(ask, ask.toBuilder().build(), "toBuilder keeps everything");
        assertNotEquals(ask, Message.assistant(""), "tool calls are part of identity");
        assertEquals("masked", ask.withContent("masked").getContent());
        assertEquals(List.of(c), ask.withContent("masked").getToolCalls());
        // plain messages are unchanged
        Message plain = Message.user("hi");
        assertTrue(plain.getToolCalls().isEmpty());
        assertNull(plain.getToolCallId());
        assertEquals(new Message(Message.Role.USER, "hi", null), plain);
    }

    @Test
    void tcm03_aPlainMessageStillSerializesAsBefore() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Message.user("hi"));
        assertFalse(json.contains("toolCalls"), json);
        assertFalse(json.contains("providerData"), json);
        Message back = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Message.class);
        assertEquals(Message.user("hi"), back);
    }

    @Test
    void tcm04_requestsCarryToolsAndCopyCompletely() {
        ToolSpec t = new ToolSpec("calc", "adds", ToolSchema.object().string("e", "expression", true).build());
        LLMRequest r = LLMRequest.builder().addUserMessage("hi").model("m").temperature(0.2).maxTokens(9).topP(0.5).stopSequences(List.of("x"))
                .addParameter("effort", "high").tools(List.of(t)).toolChoice(LLMRequest.ToolChoice.NONE).build();
        assertEquals(List.of(t), r.getTools());
        assertEquals(r, r.toBuilder().build(), "a copy drops nothing");
        assertEquals(LLMRequest.ToolChoice.NONE, r.toBuilder().build().getToolChoice());
        assertEquals(LLMRequest.ToolChoice.AUTO, LLMRequest.builder().addUserMessage("x").build().getToolChoice());
        assertTrue(LLMRequest.builder().addUserMessage("x").build().getTools().isEmpty());
        assertNotEquals(r, r.toBuilder().tools(List.of()).build());
    }

    @Test
    void tcm05_responsesCarryToolCalls() {
        ToolCall c = new ToolCall("c", "t", Map.of());
        LLMResponse r = LLMResponse.builder().content("").toolCalls(List.of(c)).providerData(Map.of("k", "v")).finishReason("tool_use").build();
        assertTrue(r.hasToolCalls());
        assertEquals(LLMResponse.FinishReason.TOOL_CALLS, r.getFinishReason());
        assertEquals(Map.of("k", "v"), r.getProviderData());
        assertFalse(LLMResponse.builder().content("x").build().hasToolCalls());
    }

    @Test
    void tcm06_clientsSayWhetherTheySupportToolsAndRefuseThemOtherwise() {
        LLMProvider plain = new FakeProvider(false);
        LLMClient client = new DefaultLLMClient(plain);
        assertFalse(client.supportsToolCalling());
        LLMRequest withTools = LLMRequest.builder().addUserMessage("hi").tools(List.of(new ToolSpec("t", "d", null))).build();
        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> client.chat(withTools));
        assertTrue(e.getMessage().contains("does not support native tool calling"), e.getMessage());
        assertEquals(0, ((FakeProvider) plain).calls, "nothing was sent");
        assertDoesNotThrow(() -> client.chat(LLMRequest.builder().addUserMessage("hi").build()), "no tools is fine");

        FakeProvider capable = new FakeProvider(true);
        assertTrue(new DefaultLLMClient(capable).supportsToolCalling());
        assertDoesNotThrow(() -> new DefaultLLMClient(capable).chat(withTools));
        assertFalse(new LLMClient() {
            public LLMResponse chat(LLMRequest r) { return null; }
            public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest r) { return null; }
        }.supportsToolCalling(), "the default is false");
    }

    @Test
    void tcm07_toolSchemaBuilderAndDefaults() {
        Map<String, Object> schema = ToolSchema.object().string("q", "query", true).integer("n", "count", false).bool("b", null, false)
                .number("x", "num", false).enumeration("mode", "which", true, List.of("a", "b")).build();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked") Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("q", "n", "b", "x", "mode"), new ArrayList<>(props.keySet()), "declaration order kept");
        assertEquals(List.of("q", "mode"), schema.get("required"));
        assertEquals("integer", ((Map<?, ?>) props.get("n")).get("type"));
        assertEquals(List.of("a", "b"), ((Map<?, ?>) props.get("mode")).get("enum"));
        assertFalse(((Map<?, ?>) props.get("b")).containsKey("description"), "blank descriptions are left out");
        assertFalse(ToolSchema.object().string("q", "d", false).build().containsKey("required"));
        io.github.llm4j.agent.Tool bare = new Scripted.Recorder("t", "r");
        assertEquals(ToolSchema.permissive(), new io.github.llm4j.agent.Tool() {
            public String getName() { return "x"; }
            public String getDescription() { return "x"; }
            public String execute(Map<String, Object> a) { return ""; }
        }.getParametersSchema());
        assertNotNull(bare.getParametersSchema());
    }

    // ── wrappers (TCP-06) ───────────────────────────────────────────────

    @Test
    void tcp06_wrappersDelegateSupportAndPassToolsThrough() {
        Scripted inner = Scripted.nativeModel(Scripted.text("ok"), Scripted.text("ok"), Scripted.text("ok"));
        LLMRequest r = LLMRequest.builder().addUserMessage("hi").tools(List.of(new ToolSpec("t", "d", null))).temperature(0.1).build();

        BudgetedLLMClient budgeted = BudgetedLLMClient.builder(inner).perCallCap(100).build();
        assertTrue(budgeted.supportsToolCalling());
        budgeted.chat(r);
        assertEquals(1, inner.last().getTools().size(), "the per-call cap copy keeps the tools");
        assertEquals(100, inner.last().getMaxTokens());
        assertEquals(0.1, inner.last().getTemperature());

        MaskingLLMClient masking = new MaskingLLMClient(inner, new RegexPIIDetector(), MaskingLLMClient.PERSONAL, c -> { });
        assertTrue(masking.supportsToolCalling());
        masking.chat(r);
        assertEquals(1, inner.last().getTools().size());

        assertFalse(new MaskingLLMClient(Scripted.textModel("x"), new RegexPIIDetector(), MaskingLLMClient.PERSONAL, c -> { }).supportsToolCalling());
    }

    @Test
    void tcp06_aBudgetedResponseKeepsItsToolCallsAndProviderData() {
        ToolCall c = new ToolCall("c1", "t", Map.of("a", 1));
        LLMResponse withCalls = LLMResponse.builder().content("").model("m").toolCalls(List.of(c)).providerData(Map.of("k", "v")).finishReason("tool_use").build();
        Scripted inner = Scripted.nativeModel(withCalls);
        BudgetedLLMClient budgeted = BudgetedLLMClient.builder(inner).budget(io.github.llm4j.budget.Budget.builder().tokens(100_000).build()).build();
        LLMResponse back = budgeted.chat(LLMRequest.builder().addUserMessage("hi").build());
        assertEquals(List.of(c), back.getToolCalls(), "no usage was reported, so the budget estimated and rebuilt the response");
        assertEquals(Map.of("k", "v"), back.getProviderData());
        assertEquals(LLMResponse.FinishReason.TOOL_CALLS, back.getFinishReason());
    }

    @Test
    void tcp06_maskingMasksToolResultsAndArgumentsAndKeepsTheStructure() {
        Scripted inner = Scripted.nativeModel(Scripted.text("ok"));
        MaskingLLMClient masking = new MaskingLLMClient(inner, new RegexPIIDetector(), MaskingLLMClient.PERSONAL, c -> { });
        ToolCall c = new ToolCall("c1", "mail", Map.of("to", "asha@example.com", "n", 3));
        masking.chat(LLMRequest.builder()
                .addUserMessage("hi")
                .addMessage(Message.assistantToolCalls("emailing asha@example.com", List.of(c), Map.of("raw", 1)))
                .addMessage(Message.toolResult("c1", "mail", "sent to asha@example.com"))
                .build());
        List<Message> sent = inner.last().getMessages();
        assertEquals("emailing [EMAIL]", sent.get(1).getContent());
        assertEquals("[EMAIL]", sent.get(1).getToolCalls().get(0).arguments().get("to"));
        assertEquals(3, sent.get(1).getToolCalls().get(0).arguments().get("n"), "non-text arguments are left alone");
        assertEquals("c1", sent.get(1).getToolCalls().get(0).id());
        assertEquals(Map.of("raw", 1), sent.get(1).getProviderData());
        assertEquals(Message.Role.TOOL, sent.get(2).getRole());
        assertEquals("sent to [EMAIL]", sent.get(2).getContent());
        assertEquals("c1", sent.get(2).getToolCallId());
        assertEquals("mail", sent.get(2).getName());
    }

    @Test
    void tcp06_routingSupportsToolsOnlyWhenEveryClientDoes() {
        RoutingLLMClient both = RoutingLLMClient.builder().strategy((req, avail) -> avail.values().iterator().next().get(0))
                .addClient(ProviderTier.FAST_CHEAP, Scripted.nativeModel()).addClient(ProviderTier.BALANCED, Scripted.nativeModel()).build();
        assertTrue(both.supportsToolCalling());
        RoutingLLMClient mixed = RoutingLLMClient.builder().strategy((req, avail) -> avail.values().iterator().next().get(0))
                .addClient(ProviderTier.FAST_CHEAP, Scripted.textModel()).addClient(ProviderTier.BALANCED, Scripted.nativeModel()).build();
        assertFalse(mixed.supportsToolCalling());
    }

    /** A provider that counts calls. */
    private static final class FakeProvider implements LLMProvider {
        final boolean tools;
        int calls;

        FakeProvider(boolean tools) { this.tools = tools; }

        @Override public LLMResponse chat(LLMRequest request) { calls++; return LLMResponse.builder().content("ok").build(); }
        @Override public String getProviderName() { return "fake"; }
        @Override public void validate() { }
        @Override public boolean supportsToolCalling() { return tools; }
    }
}
