package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Builds an agent's system prompt from what the script says: a prompt file or registry template, a
 * persona, the agent's own {@code system:} text, and its skills. {@link HarnessExecutor} owns one.
 */
final class AgentPrompts {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    /** What prompt building needs from the run. */
    interface Host {
        LoomScript script();

        /** The prompt files and registry the script refers to; null when there are none. */
        io.github.llm4j.loom.prompt.PromptCatalog promptCatalog();

        Path baseDir();
    }

    private final Host host;

    AgentPrompts(Host host) {
        this.host = host;
    }

    /**
     * Resolves the effective system prompt for an agent, in priority order: 1. {@code prompt} /
     * {@code system_template} → looked up from the prompt catalog, followed by {@code system} 2.
     * {@code persona} name → looked up from PersonaLibrary via reflection 3. Inline {@code system}
     * → used verbatim
     */
    String resolveSystemPrompt(AgentDef agentDef) {
        String base = basePrompt(agentDef);
        // Skills always apply, whatever the base prompt came from.
        String own =
                agentDef.getSkills().isEmpty()
                        ? base
                        : base + "\n\n" + resolveSkills(agentDef.getSkills());
        // The workflow's shared prompt comes first, byte for byte the same for every agent, so a
        // provider that reuses the start of a prompt can reuse it.
        String shared = sharedPrompt();
        return shared == null ? own : own.isBlank() ? shared : shared + "\n\n" + own;
    }

    /**
     * The text put in front of every agent's prompt, or null; resolved once so every agent gets the
     * same bytes.
     */
    String sharedPrompt() {
        if (sharedResolved) return shared;
        LoomScript script = host.script();
        String text = script.getSharedPrompt();
        if (text == null && script.getSharedPromptRef() != null) {
            var resolved =
                    host.promptCatalog() == null
                            ? java.util.Optional
                                    .<io.github.llm4j.loom.prompt.PromptCatalog.Resolved>empty()
                            : host.promptCatalog().resolve(script.getSharedPromptRef());
            if (resolved.isPresent()) text = resolved.get().text();
            else
                log.warning(
                        "shared prompt '"
                                + script.getSharedPromptRef()
                                + "' not found; agents run without it."); // load-time checks report
            // this first
        }
        shared = text == null || text.isBlank() ? null : text.strip();
        sharedResolved = true;
        return shared;
    }

    private String shared;
    private boolean sharedResolved;

    private String basePrompt(AgentDef agentDef) {
        // A prompt file (prompt:) or registry template (system_template:) is the base; the agent's
        // own system: follows it.
        String ref = agentDef.getPromptRef();
        if (ref != null && host.promptCatalog() != null) {
            var resolved = host.promptCatalog().resolve(ref);
            if (resolved.isPresent()) {
                String system = fallbackSystemPrompt(agentDef);
                return system.isBlank()
                        ? resolved.get().text()
                        : resolved.get().text() + "\n\n" + system;
            }
            log.warning(
                    "prompt '"
                            + ref
                            + "' not found — falling back."); // load-time checks report this first
            return fallbackSystemPrompt(agentDef);
        }

        // Persona (declared in the script, else PersonaLibrary), followed by the agent's own system
        // prompt
        if (agentDef.getPersona() != null) {
            AgentPersona persona = persona(agentDef.getPersona());
            if (persona == null)
                throw new IllegalStateException(
                        "persona "
                                + agentDef.getPersona()
                                + " is not defined"); // validated earlier
            String system = fallbackSystemPrompt(agentDef);
            return system.isBlank()
                    ? persona.toSystemPromptAddition()
                    : persona.toSystemPromptAddition() + "\n\n" + system;
        }
        return fallbackSystemPrompt(agentDef);
    }

    /** A persona declared in the script, else a {@code PersonaLibrary} one, else null. */
    private AgentPersona persona(String name) {
        for (io.github.llm4j.loom.ast.PersonaDef def : host.script().getPersonas()) {
            if (!def.getName().equals(name)) continue;
            AgentPersona.Builder b = AgentPersona.builder().name(def.getName()).role(def.getRole());
            if (def.getExpertise() != null) b.expertise(def.getExpertise());
            if (def.getTone() != null) b.tone(def.getTone());
            if (def.getDescription() != null) b.description(def.getDescription());
            def.getConstraints().forEach(b::addConstraint);
            return b.build();
        }
        return ScriptValidator.libraryPersona(name);
    }

    private String resolveSkills(List<String> skillUris) {
        StringBuilder sb = new StringBuilder("## Skills\n");
        for (String uri : skillUris) {
            try {
                AgentSkill skill = ScriptValidator.loadSkill(uri, host.baseDir());
                sb.append("\n").append(skill.toSystemPromptSection()).append("\n");
                log.info("Loaded skill: " + skill.getName());
            } catch (Exception e) {
                // validated at load time; a skill that vanished since is still worth failing on
                throw new IllegalStateException(
                        "Skill " + uri + " can't be loaded: " + e.getMessage(), e);
            }
        }
        return sb.toString();
    }

    private String fallbackSystemPrompt(AgentDef agentDef) {
        return agentDef.getSystemPrompt() != null ? agentDef.getSystemPrompt() : "";
    }
}
