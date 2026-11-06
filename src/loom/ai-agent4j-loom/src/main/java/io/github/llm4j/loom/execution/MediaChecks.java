package io.github.llm4j.loom.execution;

import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AttachItem;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ExtractMedia;
import io.github.llm4j.loom.ast.ExtractStmt;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The static checks of {@code attach} and of the files an {@code extract} reads (docs/specs/
 * 31-multimodal-input, R17): what can be known before a run is found by {@code weave check}, with the
 * line and what to change; what cannot (a path filled in at run time, an agent chosen at run time)
 * is said to be unchecked instead of guessed at.
 */
final class MediaChecks {

    private MediaChecks() {}

    static void run(ScriptValidator.Checker c) {
        LoomScript script = c.script();
        MediaPolicy policy = MediaSource.policyOf(script);
        MediaSource source =
                new MediaSource(c.context().baseDir, script.getMediaRoot(), Set.of(), policy);
        Set<String> reported = new HashSet<>();
        for (WorkflowDef workflow : script.getWorkflows()) {
            List<Statement> all = new ArrayList<>();
            StatementWalker.walk(workflow.getStatements(), all::add);
            for (Statement s : all) {
                if (s instanceof DelegateStmt d && !d.getAttachments().isEmpty()) {
                    files(c, source, policy, reported, "delegate", d.getLine(), d.getAttachments());
                    if (d.hasDynamicTarget()) {
                        if (d.getAllowedTargets().isEmpty())
                            c.warn(
                                    d.getLine(),
                                    "delegate",
                                    "the agent is chosen at run time, so whether it can take these"
                                            + " attachments is not checked; list the agents it may"
                                            + " choose with in [A, B]");
                        for (String name : d.getAllowedTargets())
                            agent(c, d.getLine(), "delegate", name, kinds(d.getAttachments()));
                    } else {
                        agent(c, d.getLine(), "delegate", d.getTargetAgent(), kinds(d.getAttachments()));
                    }
                } else if (s instanceof HandoffStmt h && !h.getAttachments().isEmpty()) {
                    files(c, source, policy, reported, "handoff", h.getLine(), h.getAttachments());
                    agent(c, h.getLine(), "handoff", h.getTargetAgent(), kinds(h.getAttachments()));
                } else if (s instanceof BroadcastStmt b && !b.getAttachments().isEmpty()) {
                    files(c, source, policy, reported, "broadcast", b.getLine(), b.getAttachments());
                    for (String name : b.getTargetAgents())
                        agent(c, b.getLine(), "broadcast", name, kinds(b.getAttachments()));
                } else if (s instanceof io.github.llm4j.loom.ast.HumanPromptStmt p
                        && !p.getAttachments().isEmpty()) {
                    // nobody's model reads it, so only the files themselves can be wrong
                    files(c, source, policy, reported, "human_prompt", p.getLine(), p.getAttachments());
                } else if (s instanceof ExtractStmt e && !e.getMediaInputs().isEmpty()) {
                    List<AttachItem> items =
                            e.getMediaInputs().stream().map(ExtractMedia::item).toList();
                    String construct = "extract " + e.getExtractor();
                    files(c, source, policy, reported, construct, e.getLine(), items);
                    extractor(c, e.getLine(), construct, e.getExtractor(), kinds(items));
                }
            }
        }
    }

    /** The folder, the count and each file that can be known now. */
    private static void files(
            ScriptValidator.Checker c,
            MediaSource source,
            MediaPolicy policy,
            Set<String> reported,
            String construct,
            int line,
            List<AttachItem> items) {
        try {
            source.checkRoot();
        } catch (MediaRefusal refused) {
            if (reported.add(refused.getMessage())) c.error(line, construct, refused.getMessage());
            return;
        }
        if (items.size() > policy.maxItemsPerMessage()) {
            c.error(
                    line,
                    construct,
                    items.size()
                            + " attachments, over the "
                            + policy.maxItemsPerMessage()
                            + " the media settings allow per task; attach fewer or raise media {"
                            + " max_items }");
        }
        for (AttachItem item : items) {
            if (item.path().contains("{")) {
                c.warn(
                        item.line(),
                        construct,
                        "attach "
                                + item.kind().label()
                                + " \""
                                + item.path()
                                + "\": the file name is filled in at run time, so it is not"
                                + " checked");
                continue;
            }
            try {
                List<Media> loaded = source.load(List.of(item), name -> name);
                if (loaded.isEmpty()) continue;
            } catch (MediaRefusal refused) {
                c.error(item.line(), construct, refused.getMessage());
            }
        }
    }

    private static void agent(
            ScriptValidator.Checker c, int line, String construct, String name, Set<MediaKind> kinds) {
        AgentDef def = c.script().getAgents().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElse(null);
        if (def == null) return; // an unknown agent is reported by the step's own check
        canTake(c, line, construct, "agent " + name + " (" + def.getModel() + ")", def.getModel(), kinds);
        AgentDef.GuardConfig guard = def.getGuard();
        if (guard != null
                && !guard.isUnscannedMedia()
                && ("mask".equals(guard.getPii()) || "block".equals(guard.getPii()))) {
            c.error(line, construct, AgentGuard.mediaRefusal(name, guard.getPii()));
        }
    }

    private static void extractor(
            ScriptValidator.Checker c,
            int line,
            String construct,
            String name,
            Set<MediaKind> kinds) {
        ExtractorDef def = c.script().findExtractor(name);
        if (def == null) return;
        canTake(c, line, construct, "extractor " + name + " (" + def.getModel() + ")", def.getModel(), kinds);
        if (!def.isUnscannedMedia() && ("mask".equals(def.getPii()) || "block".equals(def.getPii()))) {
            c.error(line, construct, ExtractRunner.mediaRefusal(name, def.getPii()));
        }
    }

    private static void canTake(
            ScriptValidator.Checker c,
            int line,
            String construct,
            String subject,
            String model,
            Set<MediaKind> kinds) {
        DefaultLLMClientFactory.mediaCapabilitiesOf(model)
                .ifPresent(
                        caps -> {
                            try {
                                caps.require(subject, kinds);
                            } catch (InvalidRequestException refused) {
                                c.error(line, construct, refused.getMessage());
                            }
                        });
    }

    private static Set<MediaKind> kinds(List<AttachItem> items) {
        return items.stream().map(AttachItem::kind).collect(Collectors.toSet());
    }
}
