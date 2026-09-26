package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.Map;

/** Openly-licensed images for B-roll and thumbnail references — the Openverse API. */
public class OpenverseBrollTool extends PublicApiTool {

    public OpenverseBrollTool(boolean offline, StudioEvents events) {
        super(offline, events);
    }

    @Override
    public String getName() {
        return "broll_finder";
    }

    @Override
    public String getDescription() {
        return "Finds openly-licensed (Creative Commons) images for B-roll or thumbnail reference, with "
                + "URLs and licence/attribution. Args: {\"query\": \"visual subject\"}.";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = arg(args, "query", "subject", "q");
        String url = "https://api.openverse.org/v1/images/?page_size=6&mature=false&q=" + enc(query);
        Fetched fetched = fetch(url, "openverse-images");

        StringBuilder out = new StringBuilder("Openly-licensed visuals for \"" + query + "\":\n");
        int n = 0;
        for (JsonNode image : fetched.json().path("results")) {
            out.append("- ").append(clip(text(image, "title"), 60))
               .append(" | image: ").append(text(image, "url"))
               .append(" | ").append(text(image, "license").toUpperCase()).append(' ')
               .append(text(image, "license_version"))
               .append(" by ").append(clip(text(image, "creator"), 40)).append('\n');
            n++;
        }
        if (n == 0) out.append("- (no images found)\n");
        return out.append(fetched.sourceLine()).toString();
    }
}
