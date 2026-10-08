package io.github.llm4j.loom.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Triggers (and their targets) to and from JSON, for the file and SQL stores. */
public final class TriggerCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TriggerCodec() { }

    public static String toJson(Trigger t) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toMap(t));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write trigger " + t.id(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Trigger fromJson(String json) {
        try {
            return fromMap(JSON.readValue(json, Map.class));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read trigger: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> toMap(Trigger t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("kind", t.kind().name());
        m.put("spec", t.spec());
        m.put("zone", t.zone().getId());
        m.put("target", targetToMap(t.target()));
        m.put("nextFire", str(t.nextFire()));
        m.put("lastFire", str(t.lastFire()));
        m.put("lastOutcome", t.lastOutcome());
        m.put("note", t.note());
        m.put("attempts", t.attempts());
        m.put("misfire", t.misfire().name());
        m.put("overlap", t.overlap().name());
        m.put("enabled", t.enabled());
        m.put("claimedBy", t.claimedBy());
        m.put("claimedAt", str(t.claimedAt()));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Trigger fromMap(Map<String, Object> m) {
        return new Trigger(
                (String) m.get("id"),
                Trigger.Kind.valueOf((String) m.get("kind")),
                (String) m.get("spec"),
                m.get("zone") == null ? null : ZoneId.of((String) m.get("zone")),
                targetFromMap((Map<String, Object>) m.get("target")),
                instant(m.get("nextFire")),
                instant(m.get("lastFire")),
                (String) m.get("lastOutcome"),
                (String) m.get("note"),
                m.get("attempts") instanceof Number n ? n.intValue() : 0,
                m.get("misfire") == null ? null : Trigger.Misfire.valueOf((String) m.get("misfire")),
                m.get("overlap") == null ? null : Trigger.Overlap.valueOf((String) m.get("overlap")),
                !Boolean.FALSE.equals(m.get("enabled")),
                (String) m.get("claimedBy"),
                instant(m.get("claimedAt")));
    }

    public static Map<String, Object> targetToMap(Trigger.Target target) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (target instanceof Trigger.ResumeRun r) {
            m.put("type", "resume");
            m.put("runId", r.runId());
        } else if (target instanceof Trigger.StartWorkflow w) {
            m.put("type", "workflow");
            m.put("script", w.script());
            m.put("workflow", w.workflow());
            m.put("args", new LinkedHashMap<>(w.args()));
        } else if (target instanceof Trigger.AgentTask a) {
            m.put("type", "agent");
            m.put("script", a.script());
            m.put("agent", a.agent());
            m.put("task", a.task());
        }
        return m;
    }

    public static String targetToJson(Trigger.Target target) {
        try {
            return JSON.writeValueAsString(targetToMap(target));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Trigger.Target targetFromJson(String json) {
        try {
            return targetFromMap(JSON.readValue(json, Map.class));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read trigger target: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Trigger.Target targetFromMap(Map<String, Object> m) {
        return switch (String.valueOf(m.get("type"))) {
            case "resume" -> new Trigger.ResumeRun((String) m.get("runId"));
            case "workflow" -> new Trigger.StartWorkflow((String) m.get("script"), (String) m.get("workflow"),
                    (Map<String, String>) m.get("args"));
            case "agent" -> new Trigger.AgentTask((String) m.get("script"), (String) m.get("agent"), (String) m.get("task"));
            default -> throw new IllegalArgumentException("Unknown trigger target type: " + m.get("type"));
        };
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    private static Instant instant(Object o) {
        return o == null ? null : Instant.parse(String.valueOf(o));
    }
}
