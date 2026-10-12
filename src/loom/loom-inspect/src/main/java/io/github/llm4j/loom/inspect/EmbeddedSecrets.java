package io.github.llm4j.loom.inspect;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.inspect.control.Controls;
import io.github.llm4j.loom.inspect.control.Secrets;
import io.github.llm4j.loom.observe.ObservableRun;
import io.github.llm4j.secret.SecretMetadata;
import io.github.llm4j.secret.SecretStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The secrets of the running app, from the store the run itself uses (so the page and the run agree about which keys exist). Lists names,
 * hosts, last update, a fingerprint and which agents use each; the one write replaces the value of a secret that is already there,
 * keeping its allowed hosts. It cannot add or remove a secret or change where one may be sent.
 */
final class EmbeddedSecrets implements Secrets {

    private final Supplier<ObservableRun> run;
    private final Clock clock;

    EmbeddedSecrets(Supplier<ObservableRun> run, Clock clock) {
        this.run = run;
        this.clock = clock;
    }

    @Override
    public List<Map<String, Object>> list() {
        ObservableRun r = run.get();
        SecretStore store = r == null ? null : r.secretStore();
        Map<String, Set<String>> usedBy = usedBy(r == null ? null : r.script());
        Set<String> names = new TreeSet<>(usedBy.keySet());
        if (store != null) names.addAll(store.names());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            boolean present = store != null && store.contains(name);
            row.put("present", present);
            row.put("usedBy", List.copyOf(usedBy.getOrDefault(name, Set.of())));
            if (present) {
                SecretMetadata md = store.metadata(name).orElse(SecretMetadata.NONE);
                row.put("allowedHosts", List.copyOf(md.allowedHosts()));
                row.put("updatedAt", md.updatedAt() == null ? null : md.updatedAt().toString());
                try {
                    row.put("fingerprint", Secrets.fingerprint(store.resolve(name)));
                } catch (RuntimeException e) {
                    row.put("fingerprint", null);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public Controls.Outcome rotate(String name, char[] value, String who) {
        ObservableRun r = run.get();
        SecretStore store = r == null ? null : r.secretStore();
        if (store == null) return Controls.Outcome.refused(409, "this run has no secret store");
        if (!store.contains(name)) return Controls.Outcome.refused(404, "there is no secret called '" + name + "' in the store; this page replaces values, it does not add secrets");
        SecretMetadata md = store.metadata(name).orElse(SecretMetadata.NONE);
        try {
            store.put(name, value, md.withUpdatedAt(clock.instant()));
        } catch (UnsupportedOperationException readOnly) {
            return Controls.Outcome.refused(409, "this store is read-only; change the value where it lives");
        } catch (RuntimeException e) {
            return Controls.Outcome.refused(500, "the store could not save it"); // never the exception's text: it could quote the value
        }
        String fp = Secrets.fingerprint(new String(value));
        return Controls.Outcome.taken("rotated; the new value is " + fp + ". A provider key applies from the next request, a tool option from the next run.");
    }

    /** secret name -> the agents that depend on it, from the script alone: {@code secret.NAME} in a provider or tool, mapped through the agents that use it. */
    static Map<String, Set<String>> usedBy(LoomScript script) {
        Map<String, Set<String>> out = new TreeMap<>();
        if (script == null) return out;
        for (ToolDef t : script.getProviders()) {
            for (String n : secretsOf(t)) {
                for (AgentDef a : script.getAgents()) {
                    if (a.getModel() != null && a.getModel().startsWith(t.getName() + "/")) out.computeIfAbsent(n, k -> new TreeSet<>()).add(a.getName() + " (provider " + t.getName() + ")");
                }
                out.computeIfAbsent(n, k -> new TreeSet<>());
            }
        }
        for (ToolDef t : script.getTools()) {
            for (String n : secretsOf(t)) {
                for (AgentDef a : script.getAgents()) {
                    if (a.getTools() != null && a.getTools().contains(t.getName())) out.computeIfAbsent(n, k -> new TreeSet<>()).add(a.getName() + " (tool " + t.getName() + ")");
                }
                out.computeIfAbsent(n, k -> new TreeSet<>());
            }
        }
        return out;
    }

    private static List<String> secretsOf(ToolDef t) {
        List<String> names = new ArrayList<>();
        t.getOptions().values().forEach(v -> {
            if (v.fromSecret()) names.add(v.value());
        });
        return names;
    }
}
