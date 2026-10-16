package io.github.llm4j.spring;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolKind;
import io.github.llm4j.secret.SecretStore;
import io.github.llm4j.tools.EmailKind;
import io.github.llm4j.tools.FileKind;
import io.github.llm4j.tools.HttpKind;
import io.github.llm4j.tools.ShellKind;
import io.github.llm4j.tools.SqlKind;
import io.github.llm4j.tools.WebhookKind;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The guarded tools of ai-agent4j-tools, made from {@code llm4j.tools.custom.<name>.kind} and {@code .options.*}: the options a Loom script would write.
 * Plain Java: {@code new WebhookKind().create("Slack", options, baseDir)}. Kept in its own class so ai-agent4j-tools is loaded only when a tool is configured.
 */
final class GuardedTools {

    private GuardedTools() { }

    static Map<String, ToolKind> kinds(Iterable<ToolKind> extra) {
        Map<String, ToolKind> kinds = new LinkedHashMap<>();
        for (ToolKind k : new ToolKind[] {new WebhookKind(), new EmailKind(), new HttpKind(), new FileKind(), new ShellKind(), new SqlKind()}) kinds.put(k.name(), k);
        for (ToolKind k : extra) kinds.put(k.name(), k);
        return kinds;
    }

    static Tool create(String name, Llm4jProperties.Tools.Custom custom, Map<String, ToolKind> kinds, SecretStore secrets, EffectContext context, Path baseDir) {
        String where = "llm4j.tools.custom." + name;
        if (custom.kind() == null) throw new Llm4jConfigurationException(where + ".kind is not set", "Use one of: " + String.join(", ", kinds.keySet()) + ".");
        ToolKind kind = kinds.get(custom.kind());
        if (kind == null) throw new Llm4jConfigurationException(where + ".kind=" + custom.kind() + " is not known", "Use one of: " + String.join(", ", kinds.keySet()) + ".");
        Map<String, String> options = resolve(where, custom.options(), secrets);
        String problem = kind.check(options, baseDir);
        if (problem != null) throw new Llm4jConfigurationException(where + ": " + problem, "Fix the options under " + where + ".options (they are the ones a Loom script writes for 'use: " + custom.kind() + "').");
        try {
            return context == null ? kind.create(name, options, baseDir) : kind.create(name, options, baseDir, context);
        } catch (Exception e) {
            throw new Llm4jConfigurationException(where + " could not be created: " + e.getMessage(), "Check the options under " + where + ".options.");
        }
    }

    /** An option {@code x-secret: NAME} becomes {@code x} with the secret's value; giving both forms is an error. */
    static Map<String, String> resolve(String where, Map<String, String> options, SecretStore secrets) {
        Map<String, String> out = new LinkedHashMap<>();
        if (options == null) return out;
        options.forEach((k, v) -> {
            if (!k.endsWith("-secret")) out.put(k, v);
        });
        options.forEach((k, v) -> {
            if (!k.endsWith("-secret")) return;
            String base = k.substring(0, k.length() - "-secret".length());
            out.put(base, CredentialProperty.value(where + ".options." + base, options.get(base), v, secrets));
        });
        return out;
    }
}
