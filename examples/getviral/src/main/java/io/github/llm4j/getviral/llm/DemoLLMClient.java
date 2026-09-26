package io.github.llm4j.getviral.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A scripted "studio model" so GetViral runs end to end with no API key — in CI, offline, or for a
 * first look. It speaks the real ReAct protocol: it calls the same tools (so live public-API data
 * flows into the content), then answers with schema-shaped JSON built from the brief and the tool
 * observations. It also answers eval4j judge prompts with a clearly labelled heuristic verdict.
 *
 * <p>It is deterministic and honest about being a demo; plug in Gemini or Ollama for real writing.
 */
public class DemoLLMClient implements LLMClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WHO = Pattern.compile("You are (?:the )?([A-Z][A-Za-z]+)");
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "for", "of", "to", "in", "on", "with", "how", "why", "what",
            "my", "your", "our", "is", "are", "be", "i", "we", "you", "that", "this", "it", "as", "at",
            "by", "from", "about", "into", "who", "when", "do", "does", "make", "get", "tips", "ideas");

    private final long paceMillis;
    private final AtomicInteger criticCalls = new AtomicInteger();

    public DemoLLMClient(long paceMillis) {
        this.paceMillis = paceMillis;
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        pace();
        String system = firstOf(request, Message.Role.SYSTEM);
        String user = firstOf(request, Message.Role.USER);
        String content = system.contains("impartial evaluator") ? judge(user) : agentTurn(system, user);
        int in = (system.length() + user.length()) / 4;
        int out = content.length() / 4;
        return LLMResponse.builder().content(content).model("demo").tokenUsage(in, out, in + out).build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }

    // ── ReAct turn ─────────────────────────────────────────────────────────────────────────────

    private String agentTurn(String system, String user) {
        Matcher who = WHO.matcher(system);
        String agent = who.find() ? who.group(1) : "Assistant";
        Turn turn = new Turn(user);
        List<Step> plan = plan(agent, turn);
        if (turn.observations.size() < plan.size()) {
            Step step = plan.get(turn.observations.size());
            return fenced(Map.of("thought", step.thought, "action", step.tool, "action_input", step.args));
        }
        Object answer = switch (agent) {
            case "Showrunner" -> showrunner(turn);
            case "TrendScout" -> trendReport(turn);
            case "Researcher" -> research(turn);
            case "Strategist" -> strategy(turn);
            case "XWriter" -> xPack(turn);
            case "ReelDirector" -> reelPack(turn);
            case "YouTubeProducer" -> youtubePack(turn);
            case "ViralityCritic" -> critique();
            case "ArtDirector" -> visuals(turn);
            case "VideoEditor" -> videoCut(turn);
            case "Publisher" -> "Publishing result: " + clip(turn.lastObservation(), 400);
            case "SafetyCoach" -> "Heads up — your brief included personal details like an email or phone number, "
                    + "and GetViral never puts personal data into public content. Remove them and hit Go again!";
            default -> "Done.";
        };
        Map<String, Object> finalTurn = new LinkedHashMap<>();
        finalTurn.put("thought", "I have everything I need.");
        finalTurn.put("final_answer", answer);
        return fenced(finalTurn);
    }

    private record Step(String tool, Map<String, Object> args, String thought) { }

    private List<Step> plan(String agent, Turn t) {
        return switch (agent) {
            case "Showrunner" -> t.question.contains("RECAST") ? List.of() : List.of(
                    new Step("viral_playbook", Map.of("query", "hook formulas for " + t.niche(), "scope", "playbook"),
                            "Grounding the creative direction in proven hook formulas."),
                    new Step("viral_playbook", Map.of("query", t.idea(), "scope", "voice"),
                            "Checking this creator's past posts to match their voice."));
            case "TrendScout" -> List.of(
                    new Step("trending_now", Map.of("limit", 8), "Checking what the internet is reading right now."),
                    new Step("hn_pulse", Map.of("query", t.keyword()), "Looking for live debates to borrow an angle from."),
                    new Step("trending_hashtags", Map.of(), "Pulling hashtags trending today."),
                    new Step("moment_calendar", Map.of("country", t.region()), "Finding upcoming moments to time the post."));
            case "Researcher" -> {
                List<Step> steps = new ArrayList<>();
                steps.add(new Step("web_search", Map.of("query", t.topic()),
                        "Searching the open web for what's true, new and debated about this idea."));
                String url = t.observations.isEmpty() ? "" : firstMatch(t.observations.get(0), "\\n\\s+(https?://\\S+)");
                if (!url.isEmpty()) {
                    steps.add(new Step("read_page", Map.of("url", url), "Reading the most relevant source in full before citing it."));
                }
                steps.add(new Step("fact_check", Map.of("query", t.keyword()), "Cross-checking the core facts on Wikipedia."));
                yield steps;
            }
            case "Strategist" -> List.of(
                    new Step("viral_playbook", Map.of("query", "hook formulas for " + t.niche(), "scope", "playbook"),
                            "Retrieving proven hook formulas for this niche."),
                    new Step("word_lab", Map.of("query", t.keyword(), "mode", "similar"),
                            "Finding sharper vocabulary for the hooks."));
            case "XWriter" -> t.revising() ? List.of() : List.of(
                    new Step("word_lab", Map.of("query", t.keyword(), "mode", "associated"),
                            "Finding words the audience associates with the topic."));
            case "ReelDirector" -> t.revising() ? List.of() : List.of(
                    new Step("trending_audio", Map.of("country", t.region().toLowerCase(Locale.ROOT)),
                            "Picking a trending sound for the Reel."),
                    new Step("broll_finder", Map.of("query", t.keyword()), "Sourcing openly-licensed B-roll."));
            case "YouTubeProducer" -> t.revising() ? List.of() : List.of(
                    new Step("broll_finder", Map.of("query", t.keyword() + " close up"),
                            "Finding a thumbnail reference image."),
                    new Step("fact_check", Map.of("query", t.keyword()), "Verifying a fact for the description."));
            case "ArtDirector" -> {
                String style = "cinematic editorial photo, warm golden-hour light, magenta and amber accents, shallow depth of field, 35mm";
                String kw = t.keyword();
                List<String> shots = t.all("shot=([^,}]+)");
                yield List.of(
                        new Step("generate_image", Map.of("purpose", "youtube_thumbnail", "aspect_ratio", "16:9",
                                "prompt", "Expressive creator reacting to " + kw + ", split before/after composition, " + style,
                                "overlay_text", orDefault(t.match("thumbnail_text=([^,}]+)"), "IT'S THIS?")),
                                "Starting with the thumbnail — it decides the click."),
                        new Step("generate_image", Map.of("purpose", "reel_cover", "aspect_ratio", "9:16",
                                "prompt", "Vertical close-up of hands mid-motion with " + kw + " props, " + style,
                                "overlay_text", orDefault(t.match("cover_text=([^,}]+)"), shortHook(t.hook()))),
                                "Now a Reel cover that holds up on the profile grid."),
                        new Step("generate_image", Map.of("purpose", "broll_1", "aspect_ratio", "9:16",
                                "prompt", (shots.isEmpty() ? "Relatable morning scene" : shots.get(Math.min(1, shots.size() - 1))) + ", " + kw + ", " + style),
                                "B-roll frame for the problem beat."),
                        new Step("generate_image", Map.of("purpose", "broll_2", "aspect_ratio", "9:16",
                                "prompt", (shots.size() > 3 ? shots.get(3) : "Calm, organised desk at sunrise") + ", " + kw + ", " + style),
                                "B-roll frame for the payoff beat."),
                        new Step("generate_image", Map.of("purpose", "x_card", "aspect_ratio", "16:9",
                                "prompt", "Minimal flat-lay that sums up " + kw + ", lots of negative space, " + style,
                                "overlay_text", "3 TINY STEPS"),
                                "And a card to attach to the first tweet."));
            }
            case "VideoEditor" -> List.of(
                    new Step("generate_video_clip", Map.of("prompt", "Slow dolly-in on a sunrise desk, " + t.keyword(), "aspect_ratio", "9:16"),
                            "Trying for one AI B-roll clip first."),
                    new Step("render_reel", Map.of("pace", t.tone().contains("calm") ? "normal" : "fast", "lead_visual", "reel_cover"),
                            "Cutting the Reel — fast pace to match the tone, cover image leads."));
            case "Publisher" -> List.of(
                    new Step("instagram_quota", Map.of(), "Checking the Instagram publishing quota first."),
                    new Step("instagram_publish", Map.of(
                            "media_type", "REELS",
                            "media_url", t.field("VIDEO_URL:"),
                            "caption", t.reelCaption()), "Publishing the Reel — this needs human approval."));
            default -> List.of();
        };
    }

    // ── Agent answers ──────────────────────────────────────────────────────────────────────────

    private Map<String, Object> showrunner(Turn t) {
        Map<String, Object> sheet = new LinkedHashMap<>();
        String topic = t.topic();
        String tone = t.tone();
        String memory = t.firstMemory();
        sheet.put("run_title", "Operation " + titleCase(t.keyword()));
        String direction = "Lead with " + tone + " energy and radical specificity: show " + topic
                + " as something a real person can try today, backed by live signals, never hype.";
        if (!memory.isEmpty()) direction += " Building on what worked before: " + memory;
        sheet.put("creative_direction", direction);

        Map<String, String> prompts = new LinkedHashMap<>();
        String who = "@" + t.creator() + ", a " + t.niche() + " creator whose tone is " + tone;
        if (t.question.contains("RECAST")) {
            String x = t.feedback("x_feedback");
            String reel = t.feedback("reel_feedback");
            String yt = t.feedback("youtube_feedback");
            prompts.put("XWriter", "Revision round for " + who + ". The critic said: \"" + x + "\". Rewrite the thread so "
                    + "tweet 1 lands a surprising, specific claim in under 12 words, cut every warm-up sentence, and end "
                    + "with a question people can answer in three words. Keep what already works.");
            prompts.put("ReelDirector", "Revision round for " + who + ". The critic said: \"" + reel + "\". Open on motion "
                    + "in the first 2 seconds with the hook as on-screen text, trim the reel under 25 seconds, and make "
                    + "the last line loop back into the first frame.");
            prompts.put("YouTubeProducer", "Revision round for " + who + ". The critic said: \"" + yt + "\". Make the "
                    + "title promise a concrete outcome under 55 characters, keep thumbnail text to 3 words that add "
                    + "tension the title doesn't, and restate the promise in the first 15 seconds.");
        } else {
            prompts.put("TrendScout", "You are scouting for " + who + ". The idea: \"" + t.idea() + "\". Find the 3 "
                    + "strongest live signals connecting it to what people care about this week: Wikipedia attention, "
                    + "Hacker News debates and trending hashtags. Report numbers exactly as the tools return them, "
                    + "flag anything that is a sample rather than live, and suggest one timely moment to post around.");
            prompts.put("Researcher", "Research \"" + t.idea() + "\" for " + who + " before anyone writes a word. Search "
                    + "the open web, read the two or three strongest sources in full, and bring back what is true, recent "
                    + "and genuinely surprising — plus what people disagree about. Every finding carries its URL; anything "
                    + "you could not verify goes under caveats. The writers may only state facts that are in your dossier.");
            prompts.put("Strategist", "Design the play for " + who + ". Creative direction: " + direction + " Write five "
                    + "hooks, each using a different formula (curiosity gap, contrarian, transformation, number list, "
                    + "direct callout), each under 12 words. Key facts come only from the research dossier and trend report, and keep their source.");
            prompts.put("XWriter", "Write for X as " + who + ". Tweet 1 must stand alone and be quotable; one idea per "
                    + "tweet, short lines, numbered 1/ to 5/, at most two hashtags in the final tweet. Voice: " + tone + ".");
            prompts.put("ReelDirector", "Direct a vertical Reel for " + who + ". Hook on screen AND spoken in the first 2 "
                    + "seconds, a new visual every 3-5 seconds, 20-30 seconds total, a loopable last line, a trending "
                    + "sound that fits the mood, and a caption ending in a save/share call to action.");
            prompts.put("YouTubeProducer", "Package a YouTube video for " + who + ". Three title options under 60 "
                    + "characters that front-load the keyword, a high-contrast thumbnail with at most 3 words, a 15-second "
                    + "hook script, chapters from 0:00, and a Shorts cut pulled from the most surprising moment.");
            prompts.put("ArtDirector", "Art-direct one coherent shoot for " + who + ": cinematic editorial photography, "
                    + "golden-hour warmth with magenta and amber accents, shallow depth of field, 35mm. Generate a 16:9 YouTube "
                    + "thumbnail with an expressive face and a before/after split, a 9:16 Reel cover that reads at grid size, two "
                    + "9:16 B-roll frames matching the Reel's problem and payoff beats, and a clean 16:9 X card. Never put words in "
                    + "the image prompt — use overlay_text, 2-4 words, high tension.");
            prompts.put("VideoEditor", "Cut the Reel for " + who + " like a top short-form editor: lead with the cover image "
                    + "for thumb-stop, keep the pace " + (tone.contains("calm") ? "measured" : "fast") + ", cut hard on every beat, "
                    + "subtitles always on for muted viewers, and end on a frame that loops into the opening.");
            prompts.put("ViralityCritic", "Judge this pack like a ruthless head of content for " + who + ". Score 0-10 on "
                    + "scroll-stopping first lines, platform fit, clarity and trust. Be specific per platform; SHIP only "
                    + "at 8 or above.");
        }
        sheet.put("prompts", prompts);
        return sheet;
    }

    private String trendReport(Turn t) {
        StringBuilder out = new StringBuilder("TREND REPORT — ").append(t.topic()).append('\n');
        String[] labels = {"Internet attention (Wikipedia most-read)", "Live debates (Hacker News)",
                "Hashtags trending today (Mastodon)", "Upcoming moments"};
        for (int i = 0; i < labels.length && i < t.observations.size(); i++) {
            out.append("• ").append(labels[i]).append(":\n");
            bullets(t.observations.get(i), 3).forEach(b -> out.append("   ").append(b).append('\n'));
        }
        Set<String> sources = new LinkedHashSet<>();
        for (String obs : t.observations) {
            Matcher m = Pattern.compile("\\[source: [^\\]]+]").matcher(obs);
            if (m.find()) sources.add(m.group());
        }
        out.append("Sources: ").append(String.join(" ", sources));
        return out.toString();
    }

    private Map<String, Object> research(Turn t) {
        String search = t.observations.isEmpty() ? "" : t.observations.get(0);
        List<Map<String, String>> findings = new ArrayList<>();
        Matcher m = Pattern.compile("\\[\\d+] ([^\\n]+)\\n\\s+(https?://\\S+)(?:\\n\\s{4}([^\\n\\[]+))?").matcher(search);
        while (m.find() && findings.size() < 4) {
            String[] head = m.group(1).split(" — ", 2);
            String snippet = m.group(3) == null ? "" : m.group(3).strip();
            Map<String, String> f = new LinkedHashMap<>();
            f.put("point", snippet.isEmpty() ? head[0] : clip(snippet, 220));
            f.put("source", head.length > 1 ? head[1] : head[0]);
            f.put("url", m.group(2));
            findings.add(f);
        }
        String check = t.lastObservation();
        String fact = firstMatch(check, "Wikipedia — [^:]+: ([^.]+\\.)");
        String factUrl = firstMatch(check, "Source: (\\S+)");
        if (!fact.isEmpty() && findings.stream().noneMatch(f -> f.get("url").equals(factUrl))) {
            findings.add(Map.of("point", fact, "source", "Wikipedia", "url", factUrl));
        }
        boolean offline = search.contains("[source: offline");
        Map<String, Object> dossier = new LinkedHashMap<>();
        dossier.put("summary", findings.isEmpty()
                ? "The open web had little on " + t.topic() + " — the pack should lean on lived experience, not claims."
                : "Researched " + t.topic() + " across " + findings.size() + " sources"
                        + (offline ? " (offline samples — connect to the internet for live research)" : "")
                        + ". The strongest material is practical and specific; claims below carry their source.");
        dossier.put("findings", findings);
        dossier.put("fresh_angles", List.of(
                "Most coverage explains what " + t.keyword() + " is; almost none shows a 2-minute way to start today.",
                "Turn the most-cited fact into a myth-vs-reality opener."));
        dossier.put("debates", List.of("Whether " + t.keyword() + " needs motivation or a system — people argue both ways."));
        dossier.put("caveats", List.of(offline
                ? "Live web search was unavailable, so findings are limited to recorded samples."
                : "Demo research: sources are real search results, but the synthesis is scripted — use Gemini for real analysis."));
        return dossier;
    }

    private Map<String, Object> strategy(Turn t) {
        String topic = t.topic();
        String kw = t.keyword();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("angle", "Show " + topic + " as a tiny, repeatable system — proof over motivation, in a "
                + t.tone() + " voice.");
        plan.put("audience_insight", capitalize(t.niche()) + " audiences scroll past generic advice; they stop for "
                + "specific, lived examples they can copy today.");
        plan.put("hooks", List.of(
                "Nobody tells you this about " + topic + "…",
                "I tried " + topic + " for 30 days. Here's what changed.",
                "Stop doing " + kw + " the hard way.",
                "3 " + kw + " mistakes I see every single day",
                "Busy, tired and into " + t.niche() + "? Save this."));
        List<String> facts = new ArrayList<>();
        Matcher found = Pattern.compile("point=(.*?), source=(.*?), url=(\\S+?)[,}]").matcher(t.question);
        while (found.find() && facts.size() < 2) {
            facts.add(clip(found.group(1).strip(), 160) + " (source: " + found.group(3) + ")");
        }
        for (String line : t.field("TRENDS:").isEmpty() ? List.<String>of() : t.block("TRENDS:")) {
            if (line.contains("views") || line.contains("points")) facts.add("Trend signal: " + line.replaceFirst("^[-•\\s]+", ""));
            if (facts.size() == 3) break;
        }
        if (facts.isEmpty()) facts.add("No live trend numbers available — keep claims experiential.");
        plan.put("key_facts", facts);
        Set<String> tags = new LinkedHashSet<>();
        tags.add("#" + camel(kw));
        tags.add("#" + camel(t.niche()));
        Matcher m = Pattern.compile("#(\\w+) \\(").matcher(t.question);
        while (m.find() && tags.size() < 5) tags.add("#" + m.group(1));
        tags.add("#GetViral");
        plan.put("hashtags", new ArrayList<>(tags));
        String moment = t.block("TRENDS:").stream().filter(l -> l.matches(".*\\d{4}-\\d{2}-\\d{2}.*")).findFirst()
                .map(l -> l.replaceFirst("^[-•\\s]+", "")).orElse("");
        plan.put("posting_moment", moment.isEmpty() ? "Post Tuesday–Thursday morning in your audience's time zone."
                : "Post this week, then ride the next moment with a follow-up: " + moment);
        return plan;
    }

    private Map<String, Object> xPack(Turn t) {
        String hook = t.hook();
        String topic = t.topic();
        String kw = t.keyword();
        boolean rev = t.revising();
        List<String> thread = new ArrayList<>();
        thread.add(rev ? hook + " (it's not what you think) 🧵" : hook + " 🧵");
        thread.add("1/ Most " + t.niche() + " advice about " + topic + " fails for one reason: it's too vague to start.");
        thread.add("2/ Shrink step one until it takes 2 minutes. If it feels too easy, it's the right size.");
        thread.add("3/ Attach it to something you already do daily. Coffee, commute, lunch — anchor beats willpower.");
        if (!rev) thread.add("4/ Track streaks, not perfection. Miss once? Fine. Never miss twice.");
        thread.add((rev ? "4/" : "5/") + " Recap: tiny start → anchor → never miss twice. Bookmark this and try step one today.");
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("thread", thread);
        pack.put("standalone", "Hot take: " + topic + " isn't a motivation problem. It's a step-size problem.");
        pack.put("reply_bait", "What's the one " + kw + " habit you'd never give up? 👇");
        return pack;
    }

    private Map<String, Object> reelPack(Turn t) {
        boolean rev = t.revising();
        String hook = t.hook();
        String song = rev ? firstMatch(t.question, "audio=(.*?) \\(trending now\\)")
                : firstSong(t.observations.isEmpty() ? "" : t.observations.get(0));
        List<Map<String, String>> beats = new ArrayList<>();
        beats.add(beat(rev ? "0-2s" : "0-3s", "Close-up, mid-motion — no intro", hook, shortHook(hook)));
        beats.add(beat(rev ? "2-6s" : "3-8s", "Relatable fail moment, handheld",
                "Here's why " + t.topic() + " never sticks…", "why it never sticks"));
        beats.add(beat(rev ? "6-11s" : "8-14s", "Quick cut: step one on screen", "Make step one so small it takes two minutes.", "STEP 1: 2 minutes"));
        beats.add(beat(rev ? "11-16s" : "14-20s", "B-roll of the daily anchor", "Glue it to something you already do.", "STEP 2: anchor it"));
        beats.add(beat(rev ? "16-21s" : "20-25s", "Calendar streak animation", "Miss once? Fine. Never twice.", "STEP 3: never miss twice"));
        beats.add(beat(rev ? "21-24s" : "25-30s", "Back to the opening frame", "…and that's why nobody tells you this.", "Save this 🔁"));
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("title", titleCase(t.keyword()) + " in 3 tiny steps");
        pack.put("duration", rev ? "24s" : "30s");
        pack.put("beats", beats);
        pack.put("caption", hook + "\n\nThe 3-step system: tiny start → daily anchor → never miss twice.\n"
                + "Save this for tomorrow morning and send it to the friend who needs it. 💬 Comment \"STEP\" for the checklist.");
        pack.put("hashtags", List.of("#" + camel(t.keyword()), "#" + camel(t.niche()), "#HabitTips", "#ReelsTips", "#GetViral"));
        pack.put("audio", song.isEmpty() ? "Upbeat trending instrumental — keep voiceover on top"
                : song + " (trending now) — duck under the voiceover");
        pack.put("cover_text", shortHook(hook));
        return pack;
    }

    private Map<String, Object> youtubePack(Turn t) {
        boolean rev = t.revising();
        String kw = titleCase(t.keyword());
        String image = firstMatch(t.observations.isEmpty() ? "" : t.observations.get(0), "image: (https?://\\S+)");
        String fact = firstMatch(t.observations.size() > 1 ? t.observations.get(1) : "", "Wikipedia — [^:]+: ([^.]+\\.)");
        String source = firstMatch(t.observations.size() > 1 ? t.observations.get(1) : "", "Source: (\\S+)");
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("titles", rev
                ? List.of(kw + ": 3 Tiny Steps That Actually Stick", "I Fixed My " + kw + " in 30 Days", "Stop Failing at " + kw)
                : List.of("The Truth About " + kw + " Nobody Tells You", "I Tried " + kw + " for 30 Days", kw + " Made Simple"));
        pack.put("thumbnail_concept", "Split frame: left, a messy 'before' desk in cold blue; right, the same scene "
                + "in warm light with a bold checkmark. Creator's face centre, eyebrows raised.");
        pack.put("thumbnail_text", rev ? "2 MINUTES?!" : "IT'S THIS?");
        pack.put("thumbnail_reference", image.isEmpty() ? "Openverse search: " + t.keyword() : image);
        pack.put("hook_script", t.hook() + " In the next eight minutes I'll show you the 3-step system that finally "
                + "made " + t.topic() + " stick — and the one mistake that kills it for most people.");
        pack.put("outline", List.of("Hook + promise", "Why it keeps failing", "Step 1: shrink it", "Step 2: anchor it",
                "Step 3: never miss twice", "Recap + next video"));
        String description = "The 3-step system that makes " + t.topic() + " stick. "
                + (fact.isEmpty() ? "" : "Background: " + fact + (source.isEmpty() ? "" : " (Source: " + source + ")"));
        List<String> sources = t.all("url=(https?://[^,}\\s]+)").stream().distinct().limit(4).toList();
        if (!sources.isEmpty()) description += "\n\nSources:\n" + sources.stream().map(u -> "- " + u).collect(java.util.stream.Collectors.joining("\n"));
        pack.put("description", description.strip());
        pack.put("chapters", List.of("0:00 The truth nobody tells you", "0:45 Why it fails", "2:10 Step 1 — shrink it",
                "3:40 Step 2 — anchor it", "5:15 Step 3 — never miss twice", "7:30 Recap"));
        pack.put("shorts_cut", "Vertical 45s: the '2-minute rule' moment from 2:10, ending on 'full system on my channel'.");
        pack.put("tags", List.of(t.keyword(), t.niche(), "habits", "self improvement", "getviral"));
        return pack;
    }

    private Map<String, Object> visuals(Turn t) {
        List<String> urls = new ArrayList<>();
        for (String obs : t.observations) urls.add(firstMatch(obs, "url: (\\S+)"));
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("style", "Cinematic editorial — golden-hour warmth with magenta/amber accents, shallow depth of field, one shoot across every platform.");
        pack.put("youtube_thumbnail", urls.size() > 0 ? urls.get(0) : "");
        pack.put("reel_cover", urls.size() > 1 ? urls.get(1) : "");
        pack.put("broll", urls.size() > 3 ? List.of(urls.get(2), urls.get(3)) : List.of());
        pack.put("x_card", urls.size() > 4 ? urls.get(4) : "");
        return pack;
    }

    private Map<String, Object> videoCut(Turn t) {
        String clip = t.observations.isEmpty() ? "" : t.observations.get(0);
        String render = t.lastObservation();
        Map<String, Object> cut = new LinkedHashMap<>();
        cut.put("video", firstMatch(render, "url: (\\S+)"));
        cut.put("duration", firstMatch(render, "Rendered a ([0-9.]+s)"));
        cut.put("edit_notes", "Cover image leads for thumb-stop, hard cuts with a flash on every beat, headline pops in "
                + "within 0.25s, subtitles on for muted viewing, last beat loops back to the first frame.");
        cut.put("ai_clip", clip.contains("url: ") ? firstMatch(clip, "url: (\\S+)") : "off (Veo not enabled)");
        return cut;
    }

    private Map<String, Object> critique() {
        int round = criticCalls.incrementAndGet();
        Map<String, Object> report = new LinkedHashMap<>();
        if (round == 1) {
            report.put("score", 7.4);
            report.put("verdict", "REVISE");
            report.put("headline", "Strong idea, but the openers are too polite — sharpen the first two seconds everywhere.");
            report.put("strengths", List.of("Clear 3-step system", "Grounded in live trend signals"));
            report.put("x_feedback", "Tweet 1 reads like a title, not a claim; add tension and cut the thread to five.");
            report.put("reel_feedback", "The opening beat is too slow — start mid-motion and trim to under 25 seconds.");
            report.put("youtube_feedback", "Titles are generic; promise a concrete outcome and add tension to the thumbnail.");
        } else {
            report.put("score", 8.9);
            report.put("verdict", "SHIP");
            report.put("headline", "Hooks now stop the scroll, every platform feels native, and claims stay grounded — ship it.");
            report.put("strengths", List.of("Scroll-stopping openers", "Loopable Reel", "Concrete YouTube promise"));
            report.put("x_feedback", "Ready.");
            report.put("reel_feedback", "Ready.");
            report.put("youtube_feedback", "Ready.");
        }
        return report;
    }

    // ── eval4j judge ───────────────────────────────────────────────────────────────────────────

    private String judge(String user) {
        int rating = 4 + Math.floorMod(user.hashCode(), 2);
        return fenced(Map.of(
                "reasoning", "Demo judge (heuristic, no LLM): the output is on-brief, specific and platform-appropriate. "
                        + "Connect Gemini or Ollama for a real LLM-as-judge verdict.",
                "rating", rating));
    }

    // ── parsing helpers ────────────────────────────────────────────────────────────────────────

    /** The ReAct scratchpad the agent sends: "Question: …" followed by Thought/Action/Observation. */
    private static final class Turn {
        final String question;
        final List<String> observations = new ArrayList<>();

        Turn(String user) {
            int q = user.indexOf("Question: ");
            String body = q >= 0 ? user.substring(q + "Question: ".length()) : user;
            String[] parts = body.split("\nObservation: ");
            String head = parts[0];
            int thought = head.indexOf("\nThought: ");
            this.question = thought >= 0 ? head.substring(0, thought) : head;
            for (int i = 1; i < parts.length; i++) {
                String obs = parts[i];
                int next = obs.indexOf("\nThought: ");
                observations.add(next >= 0 ? obs.substring(0, next) : obs);
            }
        }

        String field(String label) {
            for (String line : question.split("\n")) {
                int i = line.indexOf(label);
                if (i >= 0) return line.substring(i + label.length()).strip();
            }
            return "";
        }

        /** The multi-line block that starts at {@code label}. */
        List<String> block(String label) {
            int i = question.indexOf(label);
            if (i < 0) return List.of();
            List<String> lines = new ArrayList<>();
            for (String line : question.substring(i + label.length()).split("\n")) {
                if (!line.isBlank()) lines.add(line.strip());
            }
            return lines;
        }

        String idea() { return orDefault(field("IDEA:"), "building better habits"); }
        String niche() { return orDefault(field("NICHE:"), "lifestyle"); }
        String tone() { return orDefault(field("TONE:"), "warm and witty"); }
        String creator() { return orDefault(field("CREATOR:").replace("@", ""), "creator"); }
        String region() { return orDefault(field("REGION:"), "US").toUpperCase(Locale.ROOT); }
        boolean revising() { return question.contains("REVISE the"); }

        String hook() {
            String hook = field("HOOK:");
            return hook.isEmpty() ? "Nobody tells you this about " + topic() + "…" : hook;
        }

        String topic() {
            String idea = idea().replaceAll("[\"“”]", "").strip();
            idea = idea.replaceFirst("(?i)^(how to|why|tips for|ideas for)\\s+", "");
            String[] words = idea.split("\\s+");
            return words.length <= 7 ? idea.toLowerCase(Locale.ROOT)
                    : String.join(" ", List.of(words).subList(0, 7)).toLowerCase(Locale.ROOT);
        }

        String keyword() {
            List<String> significant = new ArrayList<>();
            for (String w : idea().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s-]", " ").split("\\s+")) {
                if (w.length() > 2 && !STOPWORDS.contains(w) && !w.matches(".*\\d.*")) significant.add(w);
            }
            if (significant.isEmpty()) return "habits";
            return String.join(" ", significant.subList(0, Math.min(2, significant.size())));
        }

        String firstMemory() {
            int i = question.indexOf("What GetViral remembers");
            if (i < 0) {
                i = question.indexOf("Context Briefing");
                if (i < 0) return "";
            }
            for (String line : question.substring(i).split("\n")) {
                String l = line.strip();
                if (l.startsWith("•") || l.startsWith("-")) return l.replaceFirst("^[•\\-]\\s*", "");
            }
            return "";
        }

        String feedback(String key) {
            Matcher m = Pattern.compile(key + "=([^,}]+)").matcher(question);
            return m.find() ? m.group(1).strip() : "tighten it";
        }

        String match(String regex) {
            Matcher m = Pattern.compile(regex).matcher(question);
            return m.find() ? m.group(1).strip() : "";
        }

        List<String> all(String regex) {
            List<String> found = new ArrayList<>();
            Matcher m = Pattern.compile(regex).matcher(question);
            while (m.find()) found.add(m.group(1).strip());
            return found;
        }

        String reelCaption() {
            Matcher caption = Pattern.compile("caption=(.*?), hashtags=", Pattern.DOTALL).matcher(question);
            Matcher tags = Pattern.compile("hashtags=\\[([^\\]]*)]").matcher(question);
            String text = caption.find() ? caption.group(1).strip() : idea();
            String hashtags = tags.find() ? tags.group(1).replace(",", "") : "#GetViral";
            return text + "\n\n" + hashtags;
        }

        String lastObservation() {
            return observations.isEmpty() ? "no result" : observations.get(observations.size() - 1);
        }
    }

    private static List<String> bullets(String observation, int max) {
        List<String> out = new ArrayList<>();
        for (String line : observation.split("\n")) {
            String l = line.strip();
            if ((l.startsWith("- ") || l.matches("^\\d+\\. .*")) && out.size() < max) out.add(l);
        }
        return out;
    }

    private static String firstSong(String observation) {
        Matcher m = Pattern.compile("1\\. (\"[^\"]+\" — [^\\n]+)").matcher(observation);
        return m.find() ? m.group(1).strip() : "";
    }


    private static String firstMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).strip() : "";
    }

    private static Map<String, String> beat(String time, String shot, String voiceover, String onScreen) {
        Map<String, String> beat = new LinkedHashMap<>();
        beat.put("time", time);
        beat.put("shot", shot);
        beat.put("voiceover", voiceover);
        beat.put("on_screen", onScreen);
        return beat;
    }

    private static String shortHook(String hook) {
        String[] words = hook.replaceAll("[…🧵.]", "").split("\\s+");
        return String.join(" ", List.of(words).subList(0, Math.min(5, words.length))).toUpperCase(Locale.ROOT);
    }

    private static String titleCase(String text) {
        StringBuilder out = new StringBuilder();
        for (String w : text.split("\\s+")) {
            if (w.isEmpty()) continue;
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return out.toString().strip();
    }

    private static String camel(String text) {
        return titleCase(text.replaceAll("[^A-Za-z0-9 ]", " ")).replace(" ", "");
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String firstOf(LLMRequest request, Message.Role role) {
        return request.getMessages().stream().filter(m -> m.getRole() == role).map(Message::getContent)
                .findFirst().orElse("");
    }

    private static String fenced(Map<String, Object> json) {
        try {
            return "```json\n" + JSON.writeValueAsString(json) + "\n```";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void pace() {
        if (paceMillis <= 0) return;
        try {
            Thread.sleep(paceMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
