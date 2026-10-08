package io.github.llm4j.loom.channel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One question: bound to one run and one step, identified by a short code. It is a plain document the stores keep as JSON.
 * The question text is stored as the step built it (so a blind {@code watch} question holds no proposal) and never holds a secret.
 */
public final class Pending {

    public enum State { OPEN, ANSWERED, EXPIRED }

    public record Delivery(String chat, String ref, Instant at) { }

    public record Answer(String text, String by, Instant at) { }

    private final String code;
    private final String run;
    private final String runId;
    private final String step;
    private final String question;
    private final List<String> choices;
    private final String kind;
    private final String to;
    private final Instant createdAt;
    private State state = State.OPEN;
    private final List<Delivery> sent = new ArrayList<>();
    private int reminders;
    private Answer answer;

    public Pending(String code, String run, String runId, String step, String question, List<String> choices, String kind, String to, Instant createdAt) {
        this.code = code;
        this.run = run;
        this.runId = runId;
        this.step = step;
        this.question = question;
        this.choices = choices == null ? List.of() : List.copyOf(choices);
        this.kind = kind;
        this.to = to;
        this.createdAt = createdAt;
    }

    public String code() { return code; }
    public String run() { return run; }
    public String runId() { return runId; }
    public String step() { return step; }
    public String question() { return question; }
    public List<String> choices() { return choices; }
    public String kind() { return kind; }
    public String to() { return to; }
    public Instant createdAt() { return createdAt; }
    public State state() { return state; }
    public List<Delivery> sent() { return sent; }
    public int reminders() { return reminders; }
    public Answer answer() { return answer; }

    /** An approval is never answered by a bare reply: the code must be in it. */
    public boolean approval() { return "approval".equals(kind); }

    public boolean open() { return state == State.OPEN; }

    public Instant lastSentAt() {
        return sent.stream().map(Delivery::at).max(Instant::compareTo).orElse(null);
    }

    public void delivered(Delivery d) { sent.add(d); }

    public void reminded() { reminders++; }

    public void answered(Answer a) {
        this.answer = a;
        this.state = State.ANSWERED;
    }

    public void expired() { this.state = State.EXPIRED; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("run", run);
        m.put("runId", runId);
        m.put("step", step);
        m.put("question", question);
        m.put("choices", choices);
        m.put("kind", kind);
        m.put("to", to);
        m.put("state", state.name().toLowerCase());
        m.put("createdAt", createdAt.toString());
        List<Map<String, Object>> sends = new ArrayList<>();
        for (Delivery d : sent) sends.add(Map.of("chat", d.chat(), "ref", d.ref(), "at", d.at().toString()));
        m.put("sent", sends);
        m.put("reminders", reminders);
        if (answer != null) m.put("answer", Map.of("text", answer.text(), "by", answer.by(), "at", answer.at().toString()));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Pending fromMap(Map<String, Object> m) {
        List<String> choices = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("choices", List.of())) choices.add(String.valueOf(o));
        Pending p = new Pending(str(m, "code"), str(m, "run"), str(m, "runId"), str(m, "step"), str(m, "question"), choices, str(m, "kind"), str(m, "to"),
                Instant.parse(str(m, "createdAt")));
        p.state = State.valueOf(str(m, "state").toUpperCase());
        for (Object o : (List<Object>) m.getOrDefault("sent", List.of())) {
            Map<String, Object> d = (Map<String, Object>) o;
            p.sent.add(new Delivery(str(d, "chat"), str(d, "ref"), Instant.parse(str(d, "at"))));
        }
        p.reminders = ((Number) m.getOrDefault("reminders", 0)).intValue();
        Object a = m.get("answer");
        if (a instanceof Map<?, ?> am) p.answer = new Answer(String.valueOf(am.get("text")), String.valueOf(am.get("by")), Instant.parse(String.valueOf(am.get("at"))));
        return p;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
