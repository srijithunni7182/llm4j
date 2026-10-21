package io.github.llm4j.loom.security;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.KnowledgeDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.autonomy.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A static security review of a Loom script: what each agent can reach, where the "lethal trifecta" (untrusted content, private data, a way out)
 * comes together in one agent, which effects go unapproved, what bounds the spend, and what comes from outside the repository. Every finding names
 * the OWASP Top 10 for LLM Applications (2025) risks it bears on. Nothing is run and nothing is contacted.
 */
public final class SecurityAudit {

    private SecurityAudit() { }

    /** What one agent can do, in the audit's terms. */
    public record AgentProfile(String agent, int line, List<String> untrusted, List<String> privateData, List<String> outward, List<String> unapprovedEffects,
                               boolean approveAll, String budget, String pii, boolean longTermMemory) {
        public boolean trifecta() {
            return !untrusted.isEmpty() && !privateData.isEmpty() && !outward.isEmpty();
        }
    }

    public static AuditReport audit(LoomScript script, String name) {
        return audit(script, name, Map.of());
    }

    /**
     * As {@link #audit(LoomScript, String)}, with what the {@code .loot} file declares tools reach ({@code Name.reach = reads}); the audit never loads
     * the tools' classes, so a tool the script does not define is believed only if the file says what it reaches.
     */
    public static AuditReport audit(LoomScript script, String name, Map<String, String> lootReach) {
        Map<String, ToolDef> tools = new LinkedHashMap<>();
        for (ToolDef t : script.getTools()) tools.put(t.getName(), t);
        List<Finding> findings = new ArrayList<>();
        List<AgentProfile> profiles = new ArrayList<>();

        for (AgentDef a : script.getAgents()) profiles.add(agent(a, script, tools, lootReach, findings));
        toolSettings(script, findings);
        actingAsInvoker(script, findings);
        supplyChain(script, findings);
        providerEndpoints(script, findings);
        classifiers(script, findings);
        if (script.getBudget() == null) {
            findings.add(new Finding("LA03", Severity.MEDIUM, List.of(Owasp.LLM10), "script", 0, "No run budget",
                    "Nothing caps what one run may spend: a loop or a manipulated agent can keep calling models.",
                    "Add a run budget at the top of the script, e.g. budget { tokens: 200000  calls: 150 }."));
        }
        decisions(script, findings);
        rewinds(script, findings);
        personRunsWhatIsWritten(script, profiles, findings);

        findings.sort(Comparator.comparing(Finding::severity).thenComparing(Finding::line).thenComparing(Finding::rule));
        return new AuditReport(name, profiles, findings, controls(script, profiles));
    }

    private static AgentProfile agent(AgentDef a, LoomScript script, Map<String, ToolDef> tools, Map<String, String> lootReach, List<Finding> findings) {
        List<String> untrusted = new ArrayList<>();
        List<String> privateData = new ArrayList<>();
        List<String> outward = new ArrayList<>();
        List<String> unapproved = new ArrayList<>();
        List<String> unapprovedOutward = new ArrayList<>();
        for (String toolName : a.getTools()) {
            ToolDef def = tools.get(toolName);
            Capabilities c = Capabilities.resolve(toolName, tools, lootReach);
            boolean approved = a.isApproveAll() || a.getApprove().contains(toolName);
            boolean unattendedShell = def != null && "shell".equals(def.getKind()) && "true".equals(Capabilities.opt(def, "unattended"));
            if (c.untrusted()) untrusted.add(toolName);
            if (c.privateData()) privateData.add(toolName);
            if (c.outward()) {
                outward.add(toolName);
                if (!approved) unapprovedOutward.add(toolName);
            }
            if (c.effect() && !approved) unapproved.add(toolName);
            if (!c.known()) {
                findings.add(new Finding("LA14", Severity.LOW, List.of(Owasp.LLM03, Owasp.LLM06), a.getName() + " / " + toolName, a.getLine(),
                        "A tool the audit can't see into", toolName + ": " + c.note() + ". The audit assumes it reads untrusted content, reaches private data and acts.",
                        "Review its code, give it the narrowest credentials, and put it under approve: unless you are sure it only reads."));
            }
            if (c.known() && c.note().contains("declared reach:")) {
                findings.add(new Finding("LA15", Severity.INFO, List.of(Owasp.LLM03, Owasp.LLM06), a.getName() + " / " + toolName, a.getLine(),
                        "A tool whose reach is its author's word", toolName + ": " + c.note() + ".",
                        "Read the tool's code once and confirm it does no more than it declares; the audit takes the declaration as written."));
            }
            if (c.effect() && !approved) {
                Severity s = unattendedShell ? Severity.HIGH : c.outward() ? Severity.MEDIUM : Severity.LOW;
                findings.add(new Finding("LA02", s, List.of(Owasp.LLM06), a.getName() + " / " + toolName, a.getLine(),
                        unattendedShell ? "A shell tool runs with nobody approving" : c.outward() ? "An effect with no approval" : "A local write with no approval",
                        toolName + " (" + c.note() + ") runs whenever " + a.getName() + " decides to call it" + (unattendedShell ? ", on this machine, unattended." : "."),
                        "Add approve: [" + toolName + "] to " + a.getName() + (c.outward() ? ", or narrow the tool so it cannot change anything." : ", or accept it: the tool is confined to its directory.")));
            }
        }
        for (String kbName : a.getKnowledgeBases()) {
            privateData.add("knowledge " + kbName);
        }
        boolean memory = a.getMemory() != null && a.getMemory().getFacts() != null;
        if (memory) privateData.add("memory facts");
        if (!a.getMcpServers().isEmpty()) {
            for (String mcp : a.getMcpServers()) {
                untrusted.add("mcp " + mcp);
                privateData.add("mcp " + mcp);
                outward.add("mcp " + mcp);
                if (!a.isApproveAll()) unapprovedOutward.add("mcp " + mcp);
            }
        }
        String pii = a.getGuard() == null ? null : a.getGuard().getPii();
        AgentProfile profile = new AgentProfile(a.getName(), a.getLine(), dedupe(untrusted), dedupe(privateData), dedupe(outward), dedupe(unapproved), a.isApproveAll(),
                a.getBudget() != null ? "own" : script.getBudget() != null ? "run budget" : "none", pii, memory);

        if (a.isSteerable()) {
            boolean exposed = profile.trifecta() || (!profile.privateData().isEmpty() && !profile.outward().isEmpty());
            findings.add(new Finding("LA18", exposed ? Severity.MEDIUM : Severity.INFO, List.of(Owasp.LLM01),
                    a.getName(), a.getLine(), exposed ? "A steerable agent can reach private data and act" : "A person's text reaches this agent while it works",
                    a.getName() + " is steerable: whoever may steer the run can put new instructions in front of it at any time" + (exposed
                            ? ", and it reaches private data (" + String.join(", ", profile.privateData()) + ") and can send or act (" + String.join(", ", profile.outward())
                            + "). A steer is checked by its guard and recorded, but it is an input channel like any other." : "."),
                    "Allow steering only for the people the run serves (the channel's allowlist), keep a guard on the agent, and leave steerable off where no one needs it."));
        }
        if (profile.trifecta()) {
            boolean open = !unapprovedOutward.isEmpty();
            findings.add(new Finding("LA01", open ? Severity.HIGH : Severity.MEDIUM, List.of(Owasp.LLM01, Owasp.LLM02, Owasp.LLM06), a.getName(), a.getLine(),
                    open ? "One agent holds the lethal trifecta with an open way out" : "One agent holds the lethal trifecta (every way out is approved)",
                    a.getName() + " reads untrusted content (" + String.join(", ", profile.untrusted()) + "), reaches private data (" + String.join(", ", profile.privateData())
                            + ") and can send or act (" + String.join(", ", profile.outward()) + "). Text it reads can tell it to send your data out"
                            + (open ? ", and nothing stops " + String.join(", ", dedupe(unapprovedOutward)) + "." : "; only your approval stands in the way."),
                    "Split the work: one agent reads the outside world, another touches your data, a third sends; connect them with expecting { ... }."));
        }
        if (!profile.privateData().isEmpty() && (pii == null || pii.equals("warn"))) {
            findings.add(new Finding("LA04", Severity.LOW, List.of(Owasp.LLM02), a.getName(), a.getLine(), "Private data with no PII guard",
                    a.getName() + " reaches " + String.join(", ", profile.privateData()) + " and sends what it sees to its model with personal data unmasked.",
                    "Add guard { pii: mask } (or pii: block where personal data must never reach the model)."));
        }
        if (memory && !profile.untrusted().isEmpty()) {
            findings.add(new Finding("LA09", Severity.MEDIUM, List.of(Owasp.LLM04, Owasp.LLM01), a.getName(), a.getLine(), "Long-term memory fed by untrusted content",
                    a.getName() + " keeps facts across runs and reads untrusted content: an injected \"fact\" can persist and steer later runs.",
                    "Keep long-term memory on agents that don't read the outside world, or review what it stores."));
        }
        return profile;
    }

    private static void toolSettings(LoomScript script, List<Finding> findings) {
        for (ToolDef t : script.getTools()) {
            String kind = t.getKind() == null ? "" : t.getKind();
            if ("true".equals(Capabilities.opt(t, "allow_private"))) {
                findings.add(new Finding("LA05", Severity.HIGH, List.of(Owasp.LLM06, Owasp.LLM02), "tool " + t.getName(), t.getLine(), "Internal addresses allowed",
                        t.getName() + " may reach private, loopback and link-local addresses, including the cloud metadata service.",
                        "Remove allow_private unless the tool must call a service on your own network, and then pin it with hosts:."));
            }
            if ("true".equals(Capabilities.opt(t, "allow_http"))) {
                findings.add(new Finding("LA05", Severity.MEDIUM, List.of(Owasp.LLM02), "tool " + t.getName(), t.getLine(), "Plain HTTP allowed",
                        t.getName() + " may send requests, and any credentials in them, without TLS.", "Use https and remove allow_http."));
            }
            if (kind.equals("shell") && "true".equals(Capabilities.opt(t, "allow_interpreters"))) {
                findings.add(new Finding("LA06", Severity.HIGH, List.of(Owasp.LLM06, Owasp.LLM05), "tool " + t.getName(), t.getLine(), "A shell tool may run interpreters",
                        "Allowing a shell or an interpreter (" + Capabilities.opt(t, "allow") + ") lets the agent run any program with any code.",
                        "Allow only the specific programs needed and remove allow_interpreters."));
            }
            if (kind.equals("email")) {
                String allowTo = Capabilities.opt(t, "allow_to");
                if (allowTo != null && allowTo.contains("*")) {
                    findings.add(new Finding("LA07", Severity.LOW, List.of(Owasp.LLM06, Owasp.LLM02), "tool " + t.getName(), t.getLine(), "The agent chooses recipients by pattern",
                            t.getName() + " lets the agent mail anyone matching " + allowTo + ".", "Prefer a fixed to:, or a short list of exact addresses."));
                }
                if ("none".equals(Capabilities.opt(t, "security")) && "true".equals(Capabilities.opt(t, "allow_insecure"))) {
                    findings.add(new Finding("LA07", Severity.MEDIUM, List.of(Owasp.LLM02), "tool " + t.getName(), t.getLine(), "Mail sent without encryption",
                            t.getName() + " talks to its SMTP server without TLS, credentials included.", "Use security: starttls or ssl."));
                }
            }
            if (kind.equals("file") && "true".equals(Capabilities.opt(t, "overwrite"))) {
                findings.add(new Finding("LA12", Severity.LOW, List.of(Owasp.LLM06), "tool " + t.getName(), t.getLine(), "Files may be overwritten",
                        t.getName() + " may replace existing files in " + Capabilities.opt(t, "root") + ".", "Leave overwrite off unless the workflow must replace its own output."));
            }
            if (kind.equals("http") && Capabilities.writes(Capabilities.opt(t, "methods")) && Capabilities.opt(t, "allow_paths") == null) {
                findings.add(new Finding("LA02", Severity.LOW, List.of(Owasp.LLM06), "tool " + t.getName(), t.getLine(), "A writing HTTP tool reaches every path",
                        t.getName() + " allows " + Capabilities.opt(t, "methods") + " on every path below " + Capabilities.opt(t, "base_url") + ".",
                        "Add allow_paths: with the paths the workflow needs."));
            }
        }
    }

    /**
     * A classifier on a hosted backend sends the state of its {@code intentclassify} steps off this machine, like any hosted model. Listed once per classifier,
     * with where it goes, which fields go, and (for a chat model that gates on its own confidence) that its confidence is uncalibrated.
     */
    private static void classifiers(LoomScript script, List<Finding> findings) {
        for (io.github.llm4j.loom.ast.IntentClassifierDef def : script.getIntentClassifiers()) {
            if (def.getModel() == null) continue;
            String service = hostedService(script, def.getModel());
            if (service == null) continue; // on this machine: nothing leaves
            java.util.Set<String> fields = new java.util.LinkedHashSet<>();
            StatementWalker.any(script, st -> {
                if (st instanceof io.github.llm4j.loom.ast.IntentClassifyStmt c && c.getClassifier().equals(def.getName())) {
                    c.getInputs().forEach(a -> fields.add(a.name().isEmpty() ? a.text() : a.name()));
                }
                return false;
            });
            boolean jev = isJev(script, def.getModel());
            boolean masked = "mask".equals(def.getPii());
            boolean uncalibrated = !jev && def.getConfidence() != null;
            findings.add(new Finding("LA19", Severity.INFO, List.of(Owasp.LLM02), "intentclassifier " + def.getName(), def.getLine(), "A classifier sends its state to a hosted service",
                    def.getName() + " sends the state of its intentclassify steps" + (fields.isEmpty() ? "" : " (" + String.join(", ", fields) + ")") + " to " + service
                            + ", so that text leaves this machine" + (masked ? "; personal data is masked first (pii: mask)." : ".")
                            + (uncalibrated ? " Its confidence is the model's own report (uncalibrated), so a confidence rule is a ranking, not a measured accuracy." : ""),
                    def.getPii() == null ? "Add pii: mask (or pii: block where personal data must never leave) to " + def.getName() + ", and treat the service as a processor of that text."
                            : "Keep pii: " + def.getPii() + " set, and treat the service as a processor of that text."));
        }
    }

    /** The hosted service a classifier model goes to, as a sentence part, or null when it stays on this machine. */
    private static String hostedService(LoomScript script, String model) {
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        int slash = model.indexOf('/');
        io.github.llm4j.loom.ast.ProviderDef declared = slash > 0
                ? script.getProviders().stream().filter(p -> p.getName().equals(model.substring(0, slash))).findFirst().orElse(null) : null;
        if (declared != null) {
            String url = Capabilities.opt(declared, "base_url");
            String host = url == null ? null : io.github.llm4j.secret.SecretMetadata.hostOf(url);
            if (host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1"))) return null;
            if ("ollama".equals(declared.getKind()) && url == null) return null;
            String kind = "jev".equals(declared.getKind()) ? "TypeSafe AI's Jev API" : "the " + declared.getKind() + " API";
            return kind + " at " + (host != null ? host : "jev".equals(declared.getKind()) ? "api.typesafe.ai" : "its default address") + " (provider " + declared.getName() + ")";
        }
        if (lower.startsWith("jev/") || lower.startsWith("jev-")) return "TypeSafe AI's Jev API (api.typesafe.ai)";
        if (lower.startsWith("ollama/") || lower.contains("llama") || lower.contains("gemma") || lower.contains("mistral")) return null;
        if (lower.contains("gemini")) return "Google (Gemini API)";
        if (lower.startsWith("anthropic/") || lower.startsWith("claude-")) return "Anthropic (Claude API)";
        if (lower.startsWith("sarvam/")) return "Sarvam AI";
        return "the provider of " + model;
    }

    private static boolean isJev(LoomScript script, String model) {
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("jev/") || lower.startsWith("jev-")) return true;
        int slash = model.indexOf('/');
        return slash > 0 && script.getProviders().stream().anyMatch(p -> p.getName().equals(model.substring(0, slash)) && "jev".equals(p.getKind()));
    }

    /** A provider that sends its key to an address the script chooses: whoever controls that address receives the key. */
    private static void providerEndpoints(LoomScript script, List<Finding> findings) {
        for (io.github.llm4j.loom.ast.ProviderDef p : script.getProviders()) {
            ToolDef.OptionValue key = p.getOptions().get("api_key");
            ToolDef.OptionValue url = p.getOptions().get("base_url");
            if (key == null || url == null) continue;
            boolean secret = key.fromSecret();
            findings.add(new Finding("LA15", secret ? Severity.LOW : Severity.MEDIUM, List.of(Owasp.LLM02, Owasp.LLM03), "provider " + p.getName(), p.getLine(),
                    "A credential is sent to a custom address",
                    "provider " + p.getName() + " sends " + key + " to " + url.value() + ". Whoever runs that address receives the key"
                            + (secret ? ", unless the stored secret is bound to hosts." : "."),
                    secret ? "Store the secret with --allow-host for that host (weave secrets set NAME --allow-host <host>), so it is refused anywhere else."
                            : "Keep the key in a secret store with a host binding (api_key: secret.NAME), and use https."));
        }
    }

    /** A tool that uses the credentials of whoever runs the workflow: the agent does whatever it can do as that person. */
    private static void actingAsInvoker(LoomScript script, List<Finding> findings) {
        for (ToolDef t : script.getTools()) {
            List<String> credentials = t.getOptions().values().stream().filter(ToolDef.OptionValue::fromInvoker).map(ToolDef.OptionValue::toString).toList();
            if (credentials.isEmpty()) continue;
            List<String> unapproved = script.getAgents().stream()
                    .filter(a -> a.getTools().contains(t.getName()) && !a.isApproveAll() && !a.getApprove().contains(t.getName()))
                    .map(AgentDef::getName).toList();
            boolean used = script.getAgents().stream().anyMatch(a -> a.getTools().contains(t.getName()));
            if (!used) continue;
            findings.add(new Finding("LA17", unapproved.isEmpty() ? Severity.LOW : Severity.MEDIUM, List.of(Owasp.LLM06, Owasp.LLM02), "tool " + t.getName(), t.getLine(),
                    "A tool that acts as the person running the workflow",
                    t.getName() + " sends " + String.join(", ", credentials) + ", so what an agent does with it is done with that person's permissions"
                            + (unapproved.isEmpty() ? " (every agent using it must have each call approved)." : ", and " + String.join(", ", unapproved) + " may call it without approval."),
                    unapproved.isEmpty() ? "Give the downstream API the narrowest scopes for the token."
                            : "Add approve: [" + t.getName() + "] to " + String.join(", ", unapproved) + ", and give the downstream API the narrowest scopes for the token."));
        }
    }

    private static void supplyChain(LoomScript script, List<Finding> findings) {
        for (McpServerDef m : script.getMcpServers()) {
            findings.add(new Finding("LA08", Severity.MEDIUM, List.of(Owasp.LLM03, Owasp.LLM06), "mcp " + m.getName(), m.getLine(), "An MCP server's tools are trusted wholesale",
                    "Every tool " + m.getName() + " offers (" + m.getCmd() + ") runs with this process's permissions, and the audit can't see what they do.",
                    "Run only servers you have reviewed, pin their versions, and give agents that use them approve: all."));
        }
        for (ToolDef t : script.getTools()) {
            String spec = Capabilities.opt(t, "spec");
            if ("openapi".equals(t.getKind()) && spec != null && spec.matches("(?i)https?://.*")) {
                findings.add(new Finding("LA08", Severity.MEDIUM, List.of(Owasp.LLM03), "tool " + t.getName(), t.getLine(), "An API description loaded from the network",
                        t.getName() + " builds its operations from " + spec + " each time the script loads: whoever controls that address decides what the agent can call.",
                        "Keep a reviewed copy of the spec in the repository."));
            }
            if ("skill_registry".equals(t.getKind())) {
                findings.add(new Finding("LA08", Severity.MEDIUM, List.of(Owasp.LLM03, Owasp.LLM01), "tool " + t.getName(), t.getLine(), "Instructions fetched from a registry",
                        t.getName() + " lets agents load skills (instructions) from " + Capabilities.opt(t, "url") + " at run time.",
                        "Prefer skills checked into the repository, or a registry you control."));
            }
        }
        for (String imp : script.getImports()) {
            if (imp.matches("(?i)https?://.*")) {
                findings.add(new Finding("LA08", Severity.MEDIUM, List.of(Owasp.LLM03), "import", 0, "A script imported from the network",
                        "The script imports " + imp + ": its agents and tools change when that address changes.", "Vendor the imported script into the repository."));
            }
        }
        for (KnowledgeDef k : script.getKnowledgeBases()) {
            findings.add(new Finding("LA13", Severity.INFO, List.of(Owasp.LLM04, Owasp.LLM08), "knowledge " + k.getName(), k.getLine(), "Indexed documents become instructions-in-waiting",
                    "Whatever is in " + k.getSource() + " is put in front of agents as context; a planted document can steer them.",
                    "Index only sources you control, and review what is added to them."));
        }
    }

    private static final java.util.regex.Pattern PROGRAM = java.util.regex.Pattern.compile("(?i)\\b(shell|bash|powershell|batch file|script|scripts|program|programs|source code|command line)\\b");
    private static final java.util.regex.Pattern WRITES = java.util.regex.Pattern.compile("(?i)\\b(writes?|author(?:s|ed)?|generat(?:es?|ed)|produc(?:es?|ed)|draft(?:s|ed)?|creates?)\\b");

    /**
     * LA16: an agent that writes programs (its persona or its prompt says it writes scripts or code) is handed text that an agent reading the outside
     * world produced, with no typed hand-off in between, and the workflow's result is something a person will read and run. The workflow itself
     * does nothing, so no other rule fires; the risk is in what the person does next.
     */
    private static void personRunsWhatIsWritten(LoomScript script, List<AgentProfile> profiles, List<Finding> findings) {
        Map<String, AgentProfile> byName = new LinkedHashMap<>();
        profiles.forEach(p -> byName.put(p.agent(), p));
        Set<String> authors = new LinkedHashSet<>();
        for (AgentDef a : script.getAgents()) if (writesPrograms(a, script)) authors.add(a.getName());
        if (authors.isEmpty()) return;
        Set<String> reported = new LinkedHashSet<>();
        for (io.github.llm4j.loom.ast.WorkflowDef w : script.getWorkflows()) {
            Map<String, io.github.llm4j.loom.ast.DelegateStmt> produced = new LinkedHashMap<>();
            io.github.llm4j.loom.ast.StatementWalker.walk(w.getStatements(), st -> {
                if (!(st instanceof io.github.llm4j.loom.ast.DelegateStmt d) || d.hasDynamicTarget() || d.hasDynamicVariable()) return;
                if (authors.contains(d.getTargetAgent()) && !reported.contains(d.getTargetAgent())) {
                    AgentProfile author = byName.get(d.getTargetAgent());
                    List<String> from = new ArrayList<>();
                    if (author != null && !author.untrusted().isEmpty()) from.add(author.agent() + " itself (" + String.join(", ", author.untrusted()) + ")");
                    for (var e : produced.entrySet()) {
                        AgentProfile source = byName.get(e.getValue().getTargetAgent());
                        boolean untypedHandOff = e.getValue().getExpecting() == null;
                        boolean reads = java.util.regex.Pattern.compile("\\{" + java.util.regex.Pattern.quote(e.getKey()) + "[.}]").matcher(d.getPayload()).find();
                        if (reads && untypedHandOff && source != null && !source.untrusted().isEmpty()) {
                            from.add(source.agent() + " (" + String.join(", ", source.untrusted()) + ") through " + e.getKey());
                        }
                    }
                    if (!from.isEmpty() && author != null) {
                        reported.add(author.agent());
                        findings.add(new Finding("LA16", Severity.INFO, List.of(Owasp.LLM01, Owasp.LLM05), author.agent(), author.line(),
                                "A person will run output that was written from untrusted text",
                                author.agent() + " writes programs, and it is given text from the outside world: " + String.join("; ", from)
                                        + ". Nothing in this script runs what it writes, so a person will. Text it read can steer what the program does.",
                                "Type the hand-off (expecting { ... }) so only fields reach the author, review the result with a different model, and keep the author free of tools."));
                    }
                }
                produced.put(d.getVariableName(), d);
            });
        }
    }

    private static boolean writesPrograms(AgentDef agent, LoomScript script) {
        StringBuilder text = new StringBuilder();
        if (agent.getSystemPrompt() != null) text.append(agent.getSystemPrompt()).append('\n');
        if (agent.getPersona() != null) {
            for (io.github.llm4j.loom.ast.PersonaDef p : script.getPersonas()) {
                if (!p.getName().equals(agent.getPersona())) continue;
                for (String part : new String[] {p.getRole(), p.getExpertise(), p.getDescription()}) if (part != null) text.append(part).append('\n');
                p.getConstraints().forEach(c -> text.append(c).append('\n'));
            }
        }
        String all = text.toString();
        return PROGRAM.matcher(all).find() && WRITES.matcher(all).find();
    }

    private static void ladders(LoomScript script, List<Finding> findings) {
        for (DecisionDef d : script.getDecisions()) {
            if (d.getLadder() == null || d.isLadderFromTrust()) continue;                  // a trust block is audited as one (LA10)
            io.github.llm4j.loom.ast.LadderDef l = script.getLadders().stream().filter(x -> x.getName().equals(d.getLadder())).findFirst().orElse(null);
            if (l == null) continue;
            int ceiling = d.getCeilingRung() == null ? l.getRungs().size() - 1 : Math.max(0, l.indexOf(d.getCeilingRung()));
            String who = "decision " + d.getName() + " / ladder " + l.getName();
            for (int i = 0; i <= ceiling && i < l.getRungs().size(); i++) {
                io.github.llm4j.loom.ast.RungDef r = l.getRungs().get(i);
                if (r.getMode() != io.github.llm4j.loom.ast.RungDef.Mode.AUTONOMOUS) continue;
                findings.add(new Finding("LA16", Severity.INFO, List.of(Owasp.LLM06), who + " / rung " + r.getName(), d.getLine(), "An autonomous rung can be reached",
                        d.getName() + " may climb to rung " + r.getName() + ", where the agent's proposals take effect without asking (ceiling: " + l.getRungs().get(ceiling).getName() + ").",
                        "Keep the ceiling below this rung until the record supports it."));
                if (l.isAutomatic()) {
                    findings.add(new Finding("LA16", Severity.MEDIUM, List.of(Owasp.LLM06), who + " / rung " + r.getName(), d.getLine(), "Climbing to act without a person",
                            "Moving up is automatic, so nobody signs off the promotion into " + r.getName() + ".", "Use climbing needs approval from NAME."));
                }
                if (r.getSlip() == null) {
                    findings.add(new Finding("LA16", Severity.MEDIUM, List.of(Owasp.LLM06, Owasp.LLM09), who + " / rung " + r.getName(), r.getLine(), "Nothing takes the agent back down",
                            "Rung " + r.getName() + " has no slip condition: once the agent is there, the record cannot move it back.", "Add slip to <lower rung> when <condition>."));
                }
                if (r.getSamplePercent() < 0.01) {
                    findings.add(new Finding("LA16", Severity.LOW, List.of(Owasp.LLM09), who + " / rung " + r.getName(), r.getLine(), "Acting with no ongoing check",
                            "Rung " + r.getName() + " sends " + (r.getSamplePercent() <= 0 ? "no" : "under 1% of") + " cases to a person blind, so drift goes unmeasured.",
                            "Add sample 5% blind."));
                }
            }
            if (d.getOnChange() == DecisionDef.OnChange.KEEP_TRUST) {
                findings.add(new Finding("LA16", ceiling >= firstAutonomous(l) && firstAutonomous(l) >= 0 ? Severity.HIGH : Severity.LOW, List.of(Owasp.LLM06), who, d.getLine(),
                        "A changed agent keeps the old agent's trust", "when the agent changes: keep the trust lets a new agent act on an old agent's record.", "Use start over."));
            }
        }
    }

    private static int firstAutonomous(io.github.llm4j.loom.ast.LadderDef l) {
        for (int i = 0; i < l.getRungs().size(); i++) if (l.getRungs().get(i).getMode() == io.github.llm4j.loom.ast.RungDef.Mode.AUTONOMOUS) return i;
        return -1;
    }

    private static void decisions(LoomScript script, List<Finding> findings) {
        ladders(script, findings);
        for (DecisionDef d : script.getDecisions()) {
            if (d.getCeiling() == Level.ACT && d.isAutomatic()) {
                findings.add(new Finding("LA10", Severity.MEDIUM, List.of(Owasp.LLM06), "decision " + d.getName(), d.getLine(), "Autonomy to act is granted without a person",
                        d.getName() + " may reach act, and moving up is automatic: the agent's choices take effect with no one signing off the promotion.",
                        "Use moving up needs approval from: <someone>, or keep never go above suggest."));
            }
            if (d.getCeiling() == Level.ACT && d.getAuditPercent() <= 0) {
                findings.add(new Finding("LA10", Severity.LOW, List.of(Owasp.LLM09), "decision " + d.getName(), d.getLine(), "Acting with no ongoing check",
                        "Once " + d.getName() + " acts, no case is checked by a person, so drift goes unmeasured.",
                        "Add check 5% of cases with a person who doesn't see the proposal."));
            }
        }
    }

    private static long countTaskSteps(LoomScript script) {
        long[] n = {0};
        for (WorkflowDef w : script.getWorkflows()) {
            StatementWalker.walk(w.getStatements(), s -> { if (s instanceof io.github.llm4j.loom.ast.RunStmt) n[0]++; });
        }
        return n[0];
    }

    private static void rewinds(LoomScript script, List<Finding> findings) {
        for (WorkflowDef w : script.getWorkflows()) {
            StatementWalker.walk(w.getStatements(), s -> {
                if (s instanceof RewindStmt r && r.getEffects() == RewindStmt.Effects.REPEAT) {
                    findings.add(new Finding("LA11", Severity.MEDIUM, List.of(Owasp.LLM06), "rewind to " + r.getTarget(), r.getLine(), "A rewind repeats side effects",
                            "Going back to " + r.getTarget() + " repeats every effect since, including messages already sent.",
                            "Use side effects: ask first (the default) or keep."));
                }
            });
        }
    }

    /** What the script itself has in place, by risk, beside what the runtime always enforces. */
    private static Map<Owasp, List<String>> controls(LoomScript script, List<AgentProfile> profiles) {
        Map<Owasp, List<String>> out = new LinkedHashMap<>();
        for (Owasp o : Owasp.values()) out.put(o, new ArrayList<>());
        long split = profiles.stream().filter(p -> !p.trifecta()).count();
        long approvals = script.getAgents().stream().filter(a -> a.isApproveAll() || !a.getApprove().isEmpty()).count();
        long guarded = profiles.stream().filter(p -> p.pii() != null && !p.pii().equals("warn")).count();
        long budgets = profiles.stream().filter(p -> p.budget().equals("own")).count();
        boolean typed = StatementWalker.any(script, s -> s instanceof io.github.llm4j.loom.ast.DelegateStmt d && d.getExpecting() != null);
        long taskSteps = countTaskSteps(script);
        if (taskSteps > 0) out.get(Owasp.LLM06).add(taskSteps + " deterministic task steps (run): plain code, no model decides them");
        out.get(Owasp.LLM01).add(split + " of " + profiles.size() + " agents without the trifecta");
        if (typed) out.get(Owasp.LLM01).add("typed hand-offs between agents (expecting)");
        out.get(Owasp.LLM02).add(guarded + " agents with a PII guard (mask or block)");
        out.get(Owasp.LLM02).add("secrets only from env.NAME (enforced at load)");
        out.get(Owasp.LLM03).add(script.getMcpServers().size() + " MCP servers; tools declared in the script only");
        out.get(Owasp.LLM04).add(script.getKnowledgeBases().size() + " knowledge bases");
        if (typed) out.get(Owasp.LLM05).add("results checked against a schema (expecting)");
        out.get(Owasp.LLM05).add("tool arguments checked by each tool's fence");
        out.get(Owasp.LLM06).add(approvals + " agents with approvals; " + script.getDecisions().size() + " decisions under earned autonomy");
        out.get(Owasp.LLM07).add("no secret can be written into a prompt (env.NAME only)");
        out.get(Owasp.LLM09).add(script.getDecisions().isEmpty() ? "no decisions measured against people" : "decisions measured against people, blind");
        out.get(Owasp.LLM10).add(script.getBudget() != null ? "a run budget" : "no run budget");
        out.get(Owasp.LLM10).add(budgets + " agents with their own budget; loops bounded by max");
        return out;
    }

    private static List<String> dedupe(List<String> in) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(in));
    }

}
