package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A copywriter's thesaurus — related words, rhymes and associations from the Datamuse API. */
public class DatamuseWordLabTool extends PublicApiTool {

    public DatamuseWordLabTool(boolean offline, StudioEvents events) {
        super(offline, events);
    }

    @Override
    public String getName() {
        return "word_lab";
    }

    @Override
    public String getDescription() {
        return "Finds punchy vocabulary for hooks and hashtags. Args: {\"query\": \"word or phrase\", "
                + "\"mode\": \"similar|rhymes|associated\"} (mode defaults to similar).";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = arg(args, "query", "word", "q");
        String mode = arg(args, "mode").toLowerCase();
        String param = switch (mode) {
            case "rhymes", "rhyme" -> "rel_rhy";
            case "associated", "triggers", "association" -> "rel_trg";
            default -> "ml";
        };
        String url = "https://api.datamuse.com/words?max=15&" + param + "=" + enc(query);
        Fetched fetched = fetch(url, "datamuse-" + param);

        List<String> words = new ArrayList<>();
        for (JsonNode word : fetched.json()) {
            words.add(text(word, "word"));
        }
        String label = switch (param) {
            case "rel_rhy" -> "Rhymes";
            case "rel_trg" -> "Associated words";
            default -> "Similar-meaning words";
        };
        return label + " for \"" + query + "\": "
                + (words.isEmpty() ? "(none)" : String.join(", ", words))
                + "\n" + fetched.sourceLine();
    }
}
