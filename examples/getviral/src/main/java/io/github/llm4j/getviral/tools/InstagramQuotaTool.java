package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.agent.Tool;
import java.util.Map;

/** Read-only: how many API posts the connected Instagram account has left in the 24h window. */
public class InstagramQuotaTool implements Tool {

    private final InstagramGraphClient client;

    public InstagramQuotaTool(InstagramGraphClient client) {
        this.client = client;
    }

    @Override
    public String getName() {
        return "instagram_quota";
    }

    @Override
    public String getDescription() {
        return "Checks the Instagram account's remaining content-publishing quota (rolling 24h). "
                + "Call before publishing. Args: {} (none).";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        if (!client.configured()) {
            return "Instagram is not connected (IG_USER_ID / IG_ACCESS_TOKEN not set). "
                    + "Publishing will run as a DRY RUN that shows the exact API calls without posting.";
        }
        JsonNode limit = client.publishingLimit();
        JsonNode entry = limit.path("data").path(0);
        return "Instagram publishing quota: " + entry.path("quota_usage").asText("?") + " used of "
                + entry.path("config").path("quota_total").asText("?") + " in the current window.";
    }
}
