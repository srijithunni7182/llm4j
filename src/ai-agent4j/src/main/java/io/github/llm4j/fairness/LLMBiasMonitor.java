package io.github.llm4j.fairness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks a model to judge a text for unfair bias. Understands context (a quoted stereotype that is
 * refuted is not bias), at the cost of a model call per check. A reply that can't be read counts as
 * no findings, with a warning: the judge never blocks on its own malfunction.
 */
public class LLMBiasMonitor implements BiasMonitor {

    private static final Logger logger = LoggerFactory.getLogger(LLMBiasMonitor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    static final String INSTRUCTIONS = """
            You review text for unfair bias against groups of people (gender, race, age, religion, \
            nationality, socioeconomic status, language). Report only statements that stereotype, \
            demean or exclude a group; quoting or refuting a stereotype is not bias.
            Reply with JSON only:
            {"findings": [{"type": "GENDER|RACIAL|AGE|RELIGIOUS|NATIONALITY|SOCIOECONOMIC|LINGUISTIC|OTHER",
                           "severity": "LOW|MEDIUM|HIGH|CRITICAL",
                           "text": "<the words>", "explanation": "<why>"}]}
            Reply {"findings": []} when there is none.""";

    private final LLMClient client;

    public LLMBiasMonitor(LLMClient client) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
    }

    @Override
    public List<BiasEvent> detectBias(String text, BiasContext context) {
        List<BiasEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;
        String reply = client.chat(LLMRequest.builder()
                .addSystemMessage(INSTRUCTIONS)
                .addUserMessage("Text to review:\n\"\"\"\n" + text + "\n\"\"\"")
                .temperature(0.0)
                .build()).getContent();
        try {
            Matcher m = JSON.matcher(reply == null ? "" : reply);
            if (!m.find()) throw new IllegalArgumentException("no JSON object in the reply");
            JsonNode findings = MAPPER.readTree(m.group()).path("findings");
            for (JsonNode f : findings) {
                events.add(BiasEvent.builder()
                        .type(enumOr(BiasType.class, f.path("type").asText(), BiasType.OTHER))
                        .severity(enumOr(BiasSeverity.class, f.path("severity").asText(), BiasSeverity.MEDIUM))
                        .text(f.path("text").asText(""))
                        .explanation(f.path("explanation").asText(""))
                        .confidence(0.7)
                        .addMetadata("judge", "llm")
                        .build());
            }
        } catch (Exception e) {
            logger.warn("Bias judge reply couldn't be read ({}); treating it as no findings", e.getMessage());
        }
        return events;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
