package io.github.llm4j.getviral.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.getviral.rag.KnowledgeBase;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Semantic search over the viral playbook and the creator's own past posts (RAG). */
public class ViralPlaybookTool implements Tool {

    private final KnowledgeBase knowledge;
    private final String creator;
    private final StudioEvents events;

    public ViralPlaybookTool(KnowledgeBase knowledge, String creator, StudioEvents events) {
        this.knowledge = knowledge;
        this.creator = creator;
        this.events = events != null ? events : StudioEvents.NONE;
    }

    @Override
    public String getName() {
        return "viral_playbook";
    }

    @Override
    public String getDescription() {
        return "Semantic search (RAG) over GetViral's playbook of proven hook formulas and platform tactics, "
                + "and over this creator's own past posts. Args: {\"query\": \"what you need\", "
                + "\"scope\": \"playbook|voice\"} (scope defaults to playbook).";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = PublicApiTool.arg(args, "query", "q", "topic");
        String scope = PublicApiTool.arg(args, "scope").toLowerCase();
        Map<String, Object> filter = new LinkedHashMap<>();
        if (scope.startsWith("voice")) {
            filter.put("kind", "voice");
            filter.put("creator", creator);
        } else {
            filter.put("kind", "playbook");
        }
        List<KnowledgeBase.Hit> hits = knowledge.search(query, 3, filter);
        events.emit("rag", Map.of(
                "query", query,
                "scope", filter.get("kind"),
                "sources", hits.stream().map(KnowledgeBase.Hit::source).toList()));
        if (hits.isEmpty()) {
            return scope.startsWith("voice")
                    ? "No past posts on file for this creator yet — infer voice from the brief."
                    : "No playbook entries matched.";
        }
        StringBuilder out = new StringBuilder();
        for (KnowledgeBase.Hit hit : hits) {
            out.append("[").append(hit.source()).append(String.format(" · relevance %.2f", hit.score()))
               .append("]\n").append(hit.text().strip()).append("\n\n");
        }
        return out.toString().strip();
    }
}
