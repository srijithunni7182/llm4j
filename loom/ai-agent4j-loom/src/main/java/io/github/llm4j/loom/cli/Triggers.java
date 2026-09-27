package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.trigger.FileTriggerStore;
import io.github.llm4j.loom.trigger.Schedules;
import io.github.llm4j.loom.trigger.Trigger;
import io.github.llm4j.loom.trigger.TriggerRunner;
import io.github.llm4j.loom.trigger.system.InstallRequest;
import io.github.llm4j.loom.trigger.system.Plan;
import io.github.llm4j.loom.trigger.system.SystemTriggerBackend;
import io.github.llm4j.loom.trigger.system.SystemTriggers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code weave triggers …} and {@code weave schedule sync}. */
final class Triggers {

    /** Options for install and uninstall. */
    record InstallOptions(String backend, String mode, String every, boolean apply, Path envFile, String url,
                          String serviceAccount, String region, Path home, String osName) { }

    static final String SYSTEM_FILE = "system.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private Triggers() { }

    static int run(String action, Path storeDir, String id, InstallOptions options, WeaveEnv env) {
        Path dir = storeDir.toAbsolutePath().normalize();
        FileTriggerStore store = new FileTriggerStore(dir);
        try {
            switch (action) {
                case "list" -> list(store, env);
                case "pause", "enable", "cancel", "fire" -> {
                    if (id == null) {
                        env.err().println("Error: " + action + " needs a trigger id (see: weave triggers list " + dir + ")");
                        return 2;
                    }
                    Trigger t = store.get(id).orElse(null);
                    if (t == null) {
                        env.err().println("Error: no trigger " + id + " in " + dir);
                        return 2;
                    }
                    switch (action) {
                        case "pause" -> store.upsert(t.withEnabled(false));
                        case "enable" -> store.upsert(t.withEnabled(true));
                        case "cancel" -> store.remove(id);
                        default -> {
                            store.upsert(t.withEnabled(true).withNextFire(env.clock().instant()));
                            new TriggerRunner(store, new WeaveTriggerTarget(dir, env), env.clock()).tick();
                        }
                    }
                    env.out().println("✓ " + action + " " + id);
                }
                case "install" -> {
                    return install(dir, store, options, env);
                }
                case "uninstall" -> {
                    return uninstall(dir, options, env);
                }
                default -> {
                    env.err().println("Error: unknown action '" + action
                            + "'. Use list, pause, enable, cancel, fire, install or uninstall.");
                    return 2;
                }
            }
            return 0;
        } catch (Exception e) {
            env.err().println("❌ " + e.getMessage());
            return 1;
        }
    }

    private static void list(FileTriggerStore store, WeaveEnv env) {
        List<Trigger> all = store.all().stream().sorted(Comparator.comparing(Trigger::id)).toList();
        if (all.isEmpty()) {
            env.out().println("No triggers in " + store.dir());
            return;
        }
        for (Trigger t : all) {
            env.out().println((t.enabled() ? "● " : "○ ") + t.id());
            env.out().println("    " + t.describe() + (t.nextFire() != null
                    ? "  next: " + WHEN.format(t.nextFire().atZone(Runs.zone(env))) : "")
                    + (t.enabled() ? "" : "  (paused)"));
            if (t.lastOutcome() != null) env.out().println("    last: " + t.lastOutcome());
            if (t.note() != null) env.out().println("    why:  " + t.note());
        }
        String system = Runs.installedSystemTrigger(store.dir());
        env.out().println(system != null ? system + " wakes this store." : "No system trigger installed for this store.");
    }

    private static InstallRequest request(Path dir, FileTriggerStore store, InstallOptions o, List<String> weave,
                                          WeaveEnv env) {
        String uid = null;
        try {
            var r = env.commands().run(Plan.Command.of("id", "-u"));
            if (r.exit() == 0) uid = r.stdout().trim();
        } catch (Exception ignored) {
            // not needed outside launchd
        }
        InstallRequest.Mode mode = "exact".equalsIgnoreCase(o.mode()) ? InstallRequest.Mode.EXACT : InstallRequest.Mode.HEARTBEAT;
        return new InstallRequest(dir, weave, mode, Schedules.parse(o.every()), store.all(), o.home(),
                env.clock().getZone(), o.envFile(), uid, o.url(), o.serviceAccount(), o.region());
    }

    static int install(Path dir, FileTriggerStore store, InstallOptions o, WeaveEnv env) throws Exception {
        String name = o.backend() != null ? o.backend() : SystemTriggers.detect(o.osName(), env.commands());
        SystemTriggerBackend backend = SystemTriggers.backend(name);
        InstallRequest req = request(dir, store, o, env.weave(), env);
        Plan plan = backend.install(req, env.commands());
        env.out().print(plan.describe());
        if (!o.apply()) {
            env.out().println("Nothing changed. Run again with --apply to install it.");
            return 0;
        }
        SystemTriggers.apply(plan, env.commands());
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("backend", name);
        saved.put("mode", req.mode().name());
        saved.put("every", o.every());
        saved.put("weave", env.weave());
        saved.put("envFile", o.envFile() == null ? null : o.envFile().toString());
        saved.put("home", o.home().toString());
        saved.put("url", o.url());
        saved.put("serviceAccount", o.serviceAccount());
        saved.put("region", o.region());
        Files.writeString(dir.resolve(SYSTEM_FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(saved));
        env.out().println("✓ Installed: " + name + " will run `weave tick` for " + dir
                + (req.mode() == InstallRequest.Mode.EXACT ? " at each trigger's time (and every " + o.every() + ")"
                        : " every " + o.every()) + ".");
        if (o.envFile() == null && !"windows".equals(name) && !"cloud-scheduler".equals(name)) {
            env.out().println("  Note: system schedulers don't see your shell's environment. If your models need API keys,"
                    + " put them in a file (chmod 600) and install with --env-file <file>.");
        }
        return 0;
    }

    static int uninstall(Path dir, InstallOptions o, WeaveEnv env) throws Exception {
        Map<String, Object> saved = saved(dir);
        String name = o.backend() != null ? o.backend()
                : saved != null ? String.valueOf(saved.get("backend")) : SystemTriggers.detect(o.osName(), env.commands());
        InstallRequest req = new InstallRequest(dir, env.weave(), null, null, List.of(), o.home(), env.clock().getZone(),
                null, uidOf(env), o.url(), o.serviceAccount(),
                o.region() != null ? o.region() : saved != null ? (String) saved.get("region") : null);
        Plan plan = SystemTriggers.backend(name).uninstall(req, env.commands());
        env.out().print(plan.describe());
        if (!o.apply()) {
            env.out().println("Nothing changed. Run again with --apply to remove it.");
            return 0;
        }
        SystemTriggers.apply(plan, env.commands());
        Files.deleteIfExists(dir.resolve(SYSTEM_FILE));
        env.out().println("✓ Removed the " + name + " trigger for " + dir + ".");
        return 0;
    }

    private static String uidOf(WeaveEnv env) {
        try {
            var r = env.commands().run(Plan.Command.of("id", "-u"));
            return r.exit() == 0 ? r.stdout().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> saved(Path dir) {
        Path file = dir.resolve(SYSTEM_FILE);
        if (!Files.exists(file)) return null;
        try {
            return JSON.readValue(file.toFile(), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** After a tick, an exact-mode system trigger is brought in line with the store's pending triggers. */
    @SuppressWarnings("unchecked")
    static void resyncExact(Path dir, FileTriggerStore store, WeaveEnv env) {
        Map<String, Object> saved = saved(dir);
        if (saved == null || !"EXACT".equals(saved.get("mode"))) return;
        try {
            InstallOptions o = new InstallOptions((String) saved.get("backend"), "exact", (String) saved.get("every"), true,
                    saved.get("envFile") == null ? null : Path.of((String) saved.get("envFile")), (String) saved.get("url"),
                    (String) saved.get("serviceAccount"), (String) saved.get("region"),
                    Path.of((String) saved.get("home")), System.getProperty("os.name"));
            List<String> weave = saved.get("weave") instanceof List<?> l ? (List<String>) l : env.weave();
            InstallRequest req = request(dir, store, o, weave, env);
            SystemTriggers.apply(SystemTriggers.backend(o.backend()).install(req, env.commands()), env.commands());
        } catch (Exception e) {
            env.err().println("⚠ Could not re-sync the exact system trigger: " + e.getMessage());
        }
    }

    static int syncSchedules(Path script, Path storeDir, WeaveEnv env) {
        try {
            LoomScript parsed = new LoomLoader().load(script.toAbsolutePath().toString());
            FileTriggerStore store = new FileTriggerStore(storeDir.toAbsolutePath().normalize());
            List<String> ids = Schedules.reconcile(parsed, script.toAbsolutePath().toString(), store, env.clock().instant());
            if (parsed.getSchedules().isEmpty()) env.out().println("No schedule blocks in " + script);
            for (String id : ids) {
                Trigger t = store.get(id).orElseThrow();
                env.out().println((t.enabled() ? "✓ " : "○ disabled ") + id + "  " + t.describe() + "  next: "
                        + WHEN.format(t.nextFire().atZone(Runs.zone(env))));
            }
            if (!parsed.getSchedules().isEmpty() && ids.isEmpty()) env.out().println("Schedules already up to date.");
            return 0;
        } catch (Exception e) {
            env.err().println("❌ " + e.getMessage());
            return 1;
        }
    }
}
