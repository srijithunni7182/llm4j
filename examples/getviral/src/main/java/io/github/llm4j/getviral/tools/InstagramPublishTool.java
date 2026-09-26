package io.github.llm4j.getviral.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.getviral.studio.StudioEvents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes a Reel, image or Story through the Instagram Content Publishing API.
 *
 * <p>This is the one action in GetViral with real-world side effects, so it declares
 * {@link #requiresApproval(Map)}: ai-agent4j's Human-in-the-Loop gate pauses the agent and the
 * studio shows the exact caption and media for a human to approve or reject. Without credentials it
 * performs a dry run and returns the exact requests it would make — it never claims to have posted.
 */
public class InstagramPublishTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HASHTAG = Pattern.compile("(^|\\s)#\\w+");
    private static final Pattern MENTION = Pattern.compile("(^|\\s)@[\\w.]+");

    private final InstagramGraphClient client;
    private final StudioEvents events;
    private final long pollIntervalMillis;
    private final int maxPolls;

    public InstagramPublishTool(InstagramGraphClient client, StudioEvents events) {
        this(client, events, 5_000, 60);
    }

    InstagramPublishTool(InstagramGraphClient client, StudioEvents events, long pollIntervalMillis, int maxPolls) {
        this.client = client;
        this.events = events != null ? events : StudioEvents.NONE;
        this.pollIntervalMillis = pollIntervalMillis;
        this.maxPolls = maxPolls;
    }

    @Override
    public String getName() {
        return "instagram_publish";
    }

    @Override
    public String getDescription() {
        return "Publishes to Instagram (requires human approval). Args: {\"media_type\": \"REELS|IMAGE|STORIES\", "
                + "\"media_url\": \"public https URL of the video/image\", \"caption\": \"caption incl. hashtags\", "
                + "\"cover_url\": \"optional Reel cover image URL\", \"share_to_feed\": true}.";
    }

    @Override
    public boolean requiresApproval(Map<String, Object> args) {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String mediaType = str(args.get("media_type"), "REELS").toUpperCase(Locale.ROOT);
        String mediaUrl = str(args.get("media_url"), "");
        String caption = str(args.get("caption"), "");
        String coverUrl = str(args.get("cover_url"), "");
        boolean shareToFeed = !"false".equalsIgnoreCase(str(args.get("share_to_feed"), "true"));

        List<String> problems = validate(mediaType, mediaUrl, caption, coverUrl);
        if (!problems.isEmpty()) {
            return "Cannot publish — fix these first: " + String.join("; ", problems);
        }

        Map<String, String> container = new LinkedHashMap<>();
        container.put("media_type", mediaType);
        boolean video = mediaType.equals("REELS") || looksLikeVideo(mediaUrl);
        container.put(video ? "video_url" : "image_url", mediaUrl);
        if (!mediaType.equals("STORIES")) container.put("caption", caption);
        if (mediaType.equals("REELS")) {
            container.put("share_to_feed", String.valueOf(shareToFeed));
            if (!coverUrl.isBlank()) container.put("cover_url", coverUrl);
        }

        if (!client.configured()) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("mode", "DRY_RUN");
            plan.put("reason", "IG_USER_ID / IG_ACCESS_TOKEN not set — nothing was posted.");
            plan.put("step1", "POST " + client.baseUrl() + "/{ig-user-id}/media " + container);
            plan.put("step2", "GET " + client.baseUrl() + "/{container-id}?fields=status_code  (poll until FINISHED)");
            plan.put("step3", "POST " + client.baseUrl() + "/{ig-user-id}/media_publish creation_id={container-id}");
            events.emit("publish", Map.of("status", "dry_run", "plan", plan));
            return JSON.writeValueAsString(plan);
        }

        events.emit("publish", Map.of("status", "creating_container", "media_type", mediaType));
        String containerId = client.createContainer(container).path("id").asText();

        String status = "IN_PROGRESS";
        for (int i = 0; i < maxPolls; i++) {
            status = client.containerStatus(containerId).path("status_code").asText("IN_PROGRESS");
            events.emit("publish", Map.of("status", "processing", "container_status", status, "poll", i + 1));
            if (status.equals("FINISHED") || status.equals("ERROR") || status.equals("EXPIRED")) break;
            Thread.sleep(pollIntervalMillis);
        }
        if (!status.equals("FINISHED")) {
            events.emit("publish", Map.of("status", "failed", "container_status", status));
            return "Instagram did not finish processing the media (status " + status + "). Nothing was published.";
        }

        String mediaId = client.publish(containerId).path("id").asText();
        JsonNode link = client.permalink(mediaId);
        String permalink = link.path("permalink").asText("");
        events.emit("publish", Map.of("status", "published", "media_id", mediaId, "permalink", permalink));
        return "Published to Instagram. media_id=" + mediaId + (permalink.isBlank() ? "" : " permalink=" + permalink);
    }

    static List<String> validate(String mediaType, String mediaUrl, String caption, String coverUrl) {
        List<String> problems = new ArrayList<>();
        if (!List.of("REELS", "IMAGE", "STORIES").contains(mediaType)) {
            problems.add("media_type must be REELS, IMAGE or STORIES");
        }
        if (!mediaUrl.startsWith("https://")) {
            problems.add("media_url must be a public https:// URL Instagram can download");
        }
        if (!coverUrl.isBlank() && !coverUrl.startsWith("https://")) {
            problems.add("cover_url must be a public https:// URL");
        }
        if (caption.length() > InstagramGraphClient.MAX_CAPTION_CHARS) {
            problems.add("caption is " + caption.length() + " chars (max " + InstagramGraphClient.MAX_CAPTION_CHARS + ")");
        }
        if (count(HASHTAG, caption) > InstagramGraphClient.MAX_HASHTAGS) {
            problems.add("caption has more than " + InstagramGraphClient.MAX_HASHTAGS + " hashtags");
        }
        if (count(MENTION, caption) > InstagramGraphClient.MAX_MENTIONS) {
            problems.add("caption has more than " + InstagramGraphClient.MAX_MENTIONS + " @mentions");
        }
        return problems;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static boolean looksLikeVideo(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".mp4") || lower.contains(".mov");
    }

    private static String str(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }
}
