package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.Map;

/** Songs everyone is hearing this week — Apple Music's public "most played" RSS (JSON) feed. */
public class TrendingAudioTool extends PublicApiTool {

    public TrendingAudioTool(boolean offline, StudioEvents events) {
        super(offline, events);
    }

    @Override
    public String getName() {
        return "trending_audio";
    }

    @Override
    public String getDescription() {
        return "Lists the most-played songs right now (Apple Music charts) to pick a Reel/Shorts "
                + "soundtrack. Args: {\"country\": \"us\"} (two-letter storefront, optional).";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String country = arg(args, "country").toLowerCase();
        if (!country.matches("[a-z]{2}")) country = "us";
        String url = "https://rss.applemarketingtools.com/api/v2/" + country + "/music/most-played/15/songs.json";
        Fetched fetched = fetch(url, "apple-most-played");

        StringBuilder out = new StringBuilder("Most-played songs (" + country.toUpperCase() + "):\n");
        int rank = 1;
        for (JsonNode song : fetched.json().path("feed").path("results")) {
            out.append(rank++).append(". \"").append(text(song, "name")).append("\" — ")
               .append(text(song, "artistName")).append('\n');
        }
        if (rank == 1) out.append("(no chart returned)\n");
        return out.append(fetched.sourceLine()).toString();
    }
}
