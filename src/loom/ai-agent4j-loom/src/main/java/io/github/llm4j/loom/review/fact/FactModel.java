package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.autonomy.expr.Suggest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fact model: the small, published catalog of things a rule can ask about. Rules are data; they
 * say what to report about these facts, and Java computes the facts. A field is added only when a
 * rule needs it, and adding one never changes what an existing rule finds.
 *
 * <p>The catalog, the documentation page ({@code weave rules facts}) and the validator that checks
 * every rule at load are all made from this one declaration, so they cannot disagree.
 */
public final class FactModel {

    /**
     * Version of the catalog. Adding a field is compatible; renaming or removing one is a new
     * major.
     */
    public static final int VERSION = 1;

    /** The root of a path that reads a declared project setting: {@code setting.data.regions}. */
    public static final String SETTING = "setting";

    private final Map<String, EntityDef> entities = new LinkedHashMap<>();

    private FactModel() {}

    /** The catalog of version 1. */
    public static FactModel v1() {
        FactModel m = new FactModel();
        m.entity("script", "The script as a whole")
                .string("file", "The script's file name")
                .number("workflowCount", "How many workflows it defines")
                .number("agentCount", "How many agents it defines")
                .bool("hasBudget", "A run budget is declared")
                .bool("isScheduled", "A schedule is declared")
                .bool("hasRateLimits", "A rate_limits block is declared")
                .bool(
                        "hasInspectRules",
                        "A <script>.inspect rules file sits beside the script, so incident thresholds are set")
                .bool(
                        "usesPausing",
                        "It can pause for a person or a timer: human_prompt, sleep, approve or decide")
                .done();
        m.entity("workflow", "One workflow")
                .string("name", "The workflow's name")
                .number("line", "The line it starts on")
                .number("stepCount", "How many statements it has, at any depth")
                .number("maxDepth", "How deeply statements are nested")
                .bool("hasReturn", "It has a return statement")
                .bool("isScheduled", "A schedule runs this workflow")
                .done();
        m.entity(
                        "step",
                        "One statement of a workflow: a node of the graph, with the id weave graph gives it")
                .string(
                        "kind",
                        "The graph node kind: delegate, classify, loop, alt, human_prompt …")
                .string("label", "The label weave graph shows")
                .number("line", "The line it is on")
                .ref("workflow", "workflow", "The workflow it is in")
                .number("depth", "How deeply it is nested in its workflow")
                .bool("inLoop", "It is inside a loop")
                .bool("inForEach", "It is inside a for each")
                .bool("retry", "It has a retry")
                .number("retryCount", "How many retries")
                .bool("timeout", "It has a timeout")
                .number("timeoutMs", "The timeout in milliseconds, 0 when none")
                .bool("onFailure", "It has an on_failure handler")
                .bool("hasMax", "A loop has a declared maximum number of rounds")
                .number("loopMax", "A loop's declared maximum, 0 when none")
                .ref("agent", "agent", "The agent a step uses, when it uses one")
                .string("bindsVariable", "The variable its result is bound to")
                .bool(
                        "readsFreeText",
                        "A branch or loop condition reads a field of an answer whose agent has no output_schema")
                .string(
                        "payloadHitsVariable",
                        "A workflow variable whose name appears inside the literal text of the prompt, empty when none")
                .bool(
                        "payloadMentionsPersonal",
                        "The prompt fills in a value named or declared as personal data")
                .bool(
                        "payloadSaysDeterministic",
                        "The prompt asks the model for something a program would do (calculate, validate a format …)")
                .refList(
                        "fanOutAgents",
                        "agent",
                        "The agents a broadcast or parallel round sends work to")
                .bool(
                        "writesSameVariableAsSibling",
                        "A parallel round whose branches bind the same variable name")
                .bool(
                        "loopBodyHasEffects",
                        "A loop whose body calls an agent that can write or send")
                .bool(
                        "checkpointBefore",
                        "A checkpoint comes earlier in the workflow, or at the start of the loop body")
                .bool(
                        "parallelizableWithPrevious",
                        "A delegate that neither reads the answer of the delegate just before it nor acts on the world, so the two could run side by side")
                .number(
                        "parallelSavingSeconds",
                        "Seconds saved by running it beside the step before it (the shorter of the two)")
                .done();
        m.entity("agent", "One agent")
                .string("name", "The agent's name")
                .number("line", "The line it is declared on")
                .ref("model", "model", "The model it runs on")
                .refList("tools", "tool", "The tools it can use")
                .bool("hasTools", "It has at least one tool")
                .list("approve", "The tools a person must approve")
                .bool("approveAll", "A person must approve every tool call")
                .bool("hasSchema", "It has an output_schema")
                .bool("hasContext", "It has a context block")
                .bool("hasMemory", "It has memory")
                .bool("feedsDecision", "A decision or ladder in the script names this agent")
                .bool(
                        "feedsBranch",
                        "A variable this agent's answer is bound to is read by an alt or loop condition")
                .bool(
                        "promptStatesFormat",
                        "Its prompt says what shape the answer must have (JSON, one of, yes or no …)")
                .bool(
                        "promptSaysWhenUnsure",
                        "Its prompt says what to do when unsure or out of scope")
                .bool(
                        "promptHasPersonalData",
                        "Its prompt holds an email address or phone number that looks real")
                .bool(
                        "hasMemoryLimit",
                        "It bounds the shared transcript with memory { max_tokens }")
                .enumeration(
                        "guardPii",
                        List.of("none", "warn", "mask", "block"),
                        "What its guard does with personal data")
                .enumeration(
                        "guardBias",
                        List.of("none", "warn", "block"),
                        "What its guard does about bias")
                .enumeration(
                        "promptSource",
                        List.of("file", "inline", "none"),
                        "Where its prompt comes from")
                .number("inlinePromptLines", "Lines of an inline prompt, 0 when none")
                .bool(
                        "systemInterpolates",
                        "An inline system prompt has a {variable} written into it")
                .number(
                        "promptTokens",
                        "Tokens in its prompt, counted as the run-time guard counts")
                .bool("promptHasSecret", "Its prompt holds something shaped like a key or password")
                .string(
                        "nearDuplicateOf",
                        "The earlier agent whose prompt it nearly copies, empty when none")
                .bool("streaming", "It streams")
                .bool("steerable", "A person can steer it while it works")
                .bool("hasEffects", "One of its tools writes or sends")
                .enumeration(
                        "maxReach",
                        List.of("none", "reads", "fetches", "writes", "sends"),
                        "The widest reach of its tools")
                .number(
                        "stepCount",
                        "How many steps use it, counting each member of a parallel or broadcast round")
                .bool(
                        "referenced",
                        "Something uses it: a step, a decision or a supervision; unknown when a step names its agent dynamically")
                .done();
        m.entity("tool", "One tool an agent can use")
                .string("name", "The tool's name")
                .number("line", "The line it is declared on, 0 when not declared in the script")
                .string(
                        "kind",
                        "Its kind: webhook, email, http, file, shell, sql, … or class for host or Java tools")
                .enumeration(
                        "reach",
                        List.of("none", "reads", "fetches", "writes", "sends"),
                        "What it can do to the outside world")
                .bool("reachKnown", "Its reach is declared or built in, not assumed")
                .bool(
                        "journaled",
                        "Its effects are journaled, so a resumed run does not repeat them")
                .bool("idempotentDeclared", "Its author declared it safe to repeat")
                .bool("approved", "Every agent that uses it needs a person's approval for it")
                .refList("usedBy", "agent", "The agents that use it")
                .done();
        m.entity("classifier", "One intentclassifier")
                .string("name", "The classifier's name")
                .number("line", "The line it is declared on")
                .ref("model", "model", "The model behind it")
                .number("questionCount", "How many questions it asks")
                .bool("hasConfidenceRule", "It has a confidence rule")
                .done();
        m.entity("model", "One model named in the script")
                .string("id", "The model id as written")
                .bool("floatingAlias", "It is an alias that moves, such as one ending in -latest")
                .string("provider", "The provider written before a slash, empty when none")
                .done();
        return m;
    }

    private Builder entity(String kind, String description) {
        return new Builder(kind, description);
    }

    public List<EntityDef> entities() {
        return new ArrayList<>(entities.values());
    }

    public Optional<EntityDef> entity(String kind) {
        return Optional.ofNullable(entities.get(kind));
    }

    /** The nearest entity kind to {@code kind}, as a hint, or an empty string. */
    public String hintEntity(String kind) {
        return Suggest.hint(kind, entities.keySet());
    }

    /** What a dotted path reaches from an entity kind: the field at its end, or a problem. */
    public PathResult resolve(String kind, String path) {
        if (path.equals(SETTING) || path.startsWith(SETTING + ".")) {
            if (path.length() <= SETTING.length() + 1)
                return PathResult.bad("'setting' needs a key, such as setting.data.regions");
            return PathResult.ok(
                    new FieldDef(
                            path,
                            FieldType.STRING,
                            null,
                            List.of(),
                            "A declared project setting",
                            1),
                    true);
        }
        EntityDef current = entities.get(kind);
        if (current == null)
            return PathResult.bad("unknown entity '" + kind + "'" + hintEntity(kind));
        String[] parts = path.split("\\.");
        if (parts.length > 3)
            return PathResult.bad("'" + path + "' goes more than three steps deep");
        FieldDef field = null;
        for (int i = 0; i < parts.length; i++) {
            Optional<FieldDef> f = current.field(parts[i]);
            if (f.isEmpty()) {
                List<String> names = current.fields().stream().map(FieldDef::name).toList();
                return PathResult.bad(
                        "'"
                                + parts[i]
                                + "' is not a fact of "
                                + current.kind()
                                + Suggest.hint(parts[i], names));
            }
            field = f.get();
            if (i < parts.length - 1) {
                if (field.type() != FieldType.ENTITY)
                    return PathResult.bad(
                            "'"
                                    + parts[i]
                                    + "' is not a reference, so '"
                                    + path
                                    + "' cannot continue past it");
                current = entities.get(field.entity());
            }
        }
        return PathResult.ok(field, false);
    }

    /** The result of {@link #resolve}: the field a path ends at, or why it is not valid. */
    public record PathResult(FieldDef field, boolean setting, String problem) {
        static PathResult ok(FieldDef f, boolean setting) {
            return new PathResult(f, setting, null);
        }

        static PathResult bad(String problem) {
            return new PathResult(null, false, problem);
        }

        public boolean valid() {
            return problem == null;
        }
    }

    /** Collects the fields of one entity while the catalog is declared. */
    private final class Builder {
        private final String kind;
        private final String description;
        private final List<FieldDef> fields = new ArrayList<>();

        Builder(String kind, String description) {
            this.kind = kind;
            this.description = description;
        }

        private Builder add(
                String name, FieldType t, String entity, List<String> values, String d) {
            fields.add(new FieldDef(name, t, entity, values, d, 1));
            return this;
        }

        Builder bool(String name, String d) {
            return add(name, FieldType.BOOL, null, List.of(), d);
        }

        Builder number(String name, String d) {
            return add(name, FieldType.NUMBER, null, List.of(), d);
        }

        Builder string(String name, String d) {
            return add(name, FieldType.STRING, null, List.of(), d);
        }

        Builder list(String name, String d) {
            return add(name, FieldType.LIST, null, List.of(), d);
        }

        Builder enumeration(String name, List<String> values, String d) {
            return add(name, FieldType.ENUM, null, values, d);
        }

        Builder ref(String name, String entity, String d) {
            return add(name, FieldType.ENTITY, entity, List.of(), d);
        }

        Builder refList(String name, String entity, String d) {
            return add(name, FieldType.ENTITY_LIST, entity, List.of(), d);
        }

        void done() {
            entities.put(kind, new EntityDef(kind, description, fields));
        }
    }
}
