package io.github.llm4j.loom.host;

import io.github.llm4j.a2a.AgentCardBuilder;
import io.github.llm4j.a2a.AgentCardView;
import io.github.llm4j.loom.ast.ExposeDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The agent card of an {@code expose} block (docs/specs/32-a2a-integration, R25), built from the
 * script alone: no network, no model, no run. It says what outsiders may send, which is text to the
 * {@code text_input} parameter and JSON for the rest, and what the agent is called and for. It says
 * nothing of the workflow inside, its agents, its channels or its tools. Signing a card and serving
 * it is the consumer's job.
 */
public final class ExposedCard {

    private static final String TEXT = "text/plain";
    private static final String JSON = "application/json";
    private static final String BINDING = "JSONRPC";
    private static final String PROTOCOL = "1.0";

    private ExposedCard() {}

    /**
     * Builds the card for one {@code expose} block.
     *
     * @param expose the block
     * @param script the script that holds it
     * @param address the https address the consumer serves the agent at
     * @return the card, ready to serve
     * @throws IllegalArgumentException if the address is not https, or the block names no workflow
     */
    public static AgentCardBuilder.Published of(ExposeDef expose, LoomScript script, String address) {
        Objects.requireNonNull(expose, "expose");
        Objects.requireNonNull(script, "script");
        requireHttps(address);
        String workflowName = expose.get("workflow");
        WorkflowDef workflow =
                script.getWorkflows().stream()
                        .filter(w -> w.getName().equals(workflowName))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("expose " + expose.getName() + " names no workflow that exists: " + workflowName));
        String description = expose.get("description", "");
        String textInput = expose.get("text_input");
        boolean takesText = textInput != null;
        boolean takesJson = workflow.getParameters().stream().anyMatch(p -> !p.equals(textInput));
        List<String> accepted = takesText ? (takesJson ? List.of(TEXT, JSON) : List.of(TEXT)) : List.of(JSON);
        AgentCardBuilder builder = AgentCardBuilder.of(expose.getName(), description);
        if (expose.getSkills().isEmpty()) {
            builder.skill(new AgentCardView.Skill(workflowName, expose.getName(), description, List.of(), List.of(), accepted, List.of(TEXT)));
        } else {
            for (ExposeDef.Skill skill : expose.getSkills())
                builder.skill(new AgentCardView.Skill(skill.id(), skill.name(), skill.description(), List.of(), List.of(), accepted, List.of(TEXT)));
        }
        accepted.forEach(builder::acceptsMediaType);
        builder.producesMediaType(TEXT);
        builder.endpoint(new AgentCardView.Endpoint(address, BINDING, PROTOCOL, null));
        return builder.build();
    }

    private static void requireHttps(String address) {
        URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(address, "address"));
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException("the address is not a web address: " + address, bad);
        }
        if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost() == null)
            throw new IllegalArgumentException("the address must be an https address, for example https://agent.example.com/a2a, not " + address);
    }
}
