package io.github.llm4j.loom.trigger.system;

import io.github.llm4j.loom.trigger.Trigger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

/**
 * What to install: a trigger that runs {@code <weave> tick <store>}.
 *
 * @param store       the trigger store directory
 * @param weave       the command that runs weave (e.g. {@code [/usr/local/bin/weave]} or {@code [java, -cp, …, WeaveCLI]})
 * @param mode        one heartbeat entry, or one entry per pending trigger (plus the heartbeat)
 * @param heartbeat   how often the heartbeat fires
 * @param pending     the store's pending triggers (exact mode)
 * @param home        the user's home directory (user-level entries only)
 * @param zone        the zone the system scheduler reads times in (cron, launchd: local time)
 * @param envFile     a file of KEY=VALUE lines (API keys) loaded before each tick, or null
 * @param uid         the user's numeric id (launchd)
 * @param url         the service URL (cloud-scheduler)
 * @param serviceAccount the service account whose OIDC token Cloud Scheduler sends (cloud-scheduler)
 * @param region      the Cloud Scheduler location (cloud-scheduler)
 */
public record InstallRequest(Path store, List<String> weave, Mode mode, Duration heartbeat, List<Trigger> pending,
                             Path home, ZoneId zone, Path envFile, String uid, String url, String serviceAccount,
                             String region) {

    public enum Mode { HEARTBEAT, EXACT }

    public InstallRequest {
        store = store.toAbsolutePath().normalize();
        weave = List.copyOf(weave);
        if (mode == null) mode = Mode.HEARTBEAT;
        if (heartbeat == null) heartbeat = Duration.ofMinutes(5);
        pending = pending == null ? List.of() : List.copyOf(pending);
        if (zone == null) zone = ZoneId.systemDefault();
    }

    /** A short stable id for the store: tags every entry installed for it. */
    public String storeId() {
        return shortHash(store.toString());
    }

    /** The command a system trigger runs: {@code <weave> tick <store>}. */
    public List<String> tickCommand() {
        java.util.ArrayList<String> argv = new java.util.ArrayList<>(weave);
        argv.add("tick");
        argv.add(store.toString());
        return argv;
    }

    /** Heartbeat minutes, at least 1. */
    public long heartbeatMinutes() {
        return Math.max(1, heartbeat.toMinutes());
    }

    static String shortHash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 4);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
