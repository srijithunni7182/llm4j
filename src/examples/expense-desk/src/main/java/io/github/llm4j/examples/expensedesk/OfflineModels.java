package io.github.llm4j.examples.expensedesk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.ExtractorPrompt;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stand-in models, so the desk runs with no key, no network and no cost. They answer in the same
 * shapes a real model does (an extractor's bare JSON, an agent's {@code final_answer}), from simple
 * pattern matching on the text they are given. They are deliberately naive: everything that matters
 * about a claim is decided by {@link ExpensePolicy}, whatever a model says.
 */
public final class OfflineModels implements LLMClientFactory {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PROMPT_TOKENS = 120;
    private static final int COMPLETION_TOKENS = 40;

    private static final Pattern SYMBOL_AMOUNT = Pattern.compile("([$€₹])\\s*(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern CODE_AMOUNT =
            Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(USD|EUR|INR|GBP|JPY|dollars|euros|rupees)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERCHANT =
            Pattern.compile("\\b(?:at|from|with|on)\\s+((?:[A-Z][\\w&'.-]*)(?:\\s+[A-Z][\\w&'.-]*){0,3})");
    private static final Pattern REFUSED = Pattern.compile("refused by policy: (.+)");
    private static final Pattern PAID = Pattern.compile("paid with reference (PAY-\\d+)");

    private static final Map<String, List<String>> CATEGORY_WORDS =
            Map.of(
                    "travel", List.of("taxi", "cab", "flight", "train", "hotel", "uber", "airline", "mileage"),
                    "meals", List.of("lunch", "dinner", "breakfast", "coffee", "restaurant", "meal"),
                    "equipment", List.of("laptop", "keyboard", "monitor", "mouse", "headset", "chair", "desk"));

    @Override
    public LLMClient createClient(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                List<Message> messages = request.getMessages();
                String system = messages.isEmpty() ? "" : text(messages.get(0));
                String last = messages.isEmpty() ? "" : text(messages.get(messages.size() - 1));
                String content = system.contains(ExtractorPrompt.ROLE) ? json(readClaim(last)) : envelope(note(last));
                return LLMResponse.builder()
                        .content(content)
                        .model(model)
                        .tokenUsage(PROMPT_TOKENS, COMPLETION_TOKENS, PROMPT_TOKENS + COMPLETION_TOKENS)
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    /** The fields of a claim, read from its text. What the text does not state is left out. */
    static Map<String, Object> readClaim(String text) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Matcher merchant = MERCHANT.matcher(text);
        if (merchant.find()) fields.put("merchant", merchant.group(1));
        Matcher symbol = SYMBOL_AMOUNT.matcher(text);
        Matcher code = CODE_AMOUNT.matcher(text);
        if (symbol.find()) {
            fields.put("amount", number(symbol.group(2)));
            fields.put("currency", switch (symbol.group(1)) {
                case "$" -> "USD";
                case "€" -> "EUR";
                default -> "INR";
            });
        } else if (code.find()) {
            fields.put("amount", number(code.group(1)));
            fields.put("currency", currency(code.group(2)));
        }
        fields.put("category", category(text));
        return fields;
    }

    private static double number(String digits) {
        return Double.parseDouble(digits.replace(",", ""));
    }

    private static String currency(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "usd", "dollars" -> "USD";
            case "eur", "euros" -> "EUR";
            case "inr", "rupees" -> "INR";
            default -> "OTHER";
        };
    }

    private static String category(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return CATEGORY_WORDS.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(lower::contains))
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse("other");
    }

    /** The clerk's note, from the outcome the workflow told it. */
    static String note(String task) {
        Matcher refused = REFUSED.matcher(task);
        if (refused.find()) {
            return "We could not pay this claim: " + refused.group(1).strip() + ". Please get in touch if you think this is wrong.";
        }
        Matcher paid = PAID.matcher(task);
        if (paid.find()) {
            return "Your claim has been paid. The reference is " + paid.group(1) + ".";
        }
        return "Your claim was looked at by an approver and was not approved. Please talk to your manager about it.";
    }

    private static String envelope(String answer) {
        return "```json\n" + json(Map.of("thought", "write the note", "final_answer", answer)) + "\n```";
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a map of text and numbers could not be written as JSON", e);
        }
    }

    private static String text(Message message) {
        String content = message.getContent();
        return content == null ? "" : content;
    }
}
