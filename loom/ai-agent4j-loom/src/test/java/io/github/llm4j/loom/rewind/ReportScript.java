package io.github.llm4j.loom.rewind;

import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.model.LLMRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The self-correcting report workflow the rewind tests share, with a scripted model: a reviewer that scores each draft as told. */
final class ReportScript {

    static final String SCRIPT = """
            agent Collector { model: "m" system: "You are Collector." }
            agent Analyst   { model: "m" system: "You are Analyst." }
            agent Writer    { model: "m" system: "You are Writer." }
            agent Reviewer  { model: "m" system: "You are Reviewer." }
            agent Publisher { model: "m" system: "You are Publisher." }
            workflow Report(topic) {
                checkpoint collected  starting with feedback = "none"
                delegate "Collect sources on {topic}. Feedback so far: {feedback}" to Collector -> data
                delegate "Analyse {data}" to Analyst -> analysis
                delegate "Write the report from {analysis}" to Writer -> draft
                delegate "Review {draft}" to Reviewer -> review expecting { score: number, notes: string }
                rewind to collected
                    when (review.score < 7)
                    at most 2 times
                    carrying feedback = "{review.notes}"
                    if it still fails { note "gave up" }
                delegate "Publish {draft}" to Publisher -> published
            }
            """;

    /** Calls made, by agent, in order. */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final List<String> tasks = Collections.synchronizedList(new ArrayList<>());
    private final int[] scores;

    ReportScript(int... scores) {
        this.scores = scores;
    }

    /**
     * The model is a pure function of what it is asked, so a run that crashes and resumes meets the same answers. Feedback travels in the
     * text: the collector echoes it, so the draft carries it, and the reviewer scores by how many rounds of feedback the draft shows.
     */
    String reply(LLMRequest request) {
        String system = request.getMessages().get(0).getContent();
        String message = ScriptedRun.lastMessage(request);
        int marker = message.lastIndexOf("Current Task:");
        String task = marker < 0 ? message : message.substring(marker + "Current Task:".length()).trim();
        String agent = system.replace("You are ", "").replace(".", "").trim();
        calls.add(agent);
        tasks.add(agent + ": " + task);
        switch (agent) {
            case "Collector": {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("Feedback so far: (\\S+)").matcher(task);
                return ScriptedRun.done("data(" + (m.find() ? m.group(1) : "?") + ")");
            }
            case "Analyst":
                return ScriptedRun.done("analysis of " + task.replaceAll("^.*Analyse ", "").replaceAll("[\\r\\n].*", ""));
            case "Writer":
                return ScriptedRun.done("draft from " + task.replaceAll("^.*Write the report from ", "").replaceAll("[\\r\\n].*", ""));
            case "Reviewer": {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("fix(\\d+)").matcher(task);
                int round = m.find() ? Integer.parseInt(m.group(1)) : 0;
                int score = scores[Math.min(round, scores.length - 1)];
                return "```json\n{\"score\": " + score + ", \"notes\": \"fix" + (round + 1) + "\"}\n```";
            }
            default:
                return ScriptedRun.done("published");
        }
    }

    long count(String agent) {
        return calls.stream().filter(agent::equals).count();
    }
}
