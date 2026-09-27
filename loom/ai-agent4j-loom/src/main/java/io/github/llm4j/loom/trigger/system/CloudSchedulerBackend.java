package io.github.llm4j.loom.trigger.system;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Cloud Scheduler calling the host's tick endpoint ({@code POST <url>/loom/tick}, see
 * {@link io.github.llm4j.loom.trigger.TriggerEndpoint}) with an OIDC token — for a service that scales to
 * zero, with a SQL trigger store. Heartbeat only: Cloud Scheduler has minute resolution and one job is enough.
 */
public final class CloudSchedulerBackend implements SystemTriggerBackend {

    @Override
    public String name() {
        return "cloud-scheduler";
    }

    static String job(InstallRequest r) {
        return "loom-" + r.storeId();
    }

    @Override
    public Plan install(InstallRequest r, CommandRunner runner) {
        if (r.url() == null || r.url().isBlank()) {
            throw new IllegalArgumentException("cloud-scheduler needs --url (the service that exposes /loom/tick)");
        }
        String region = r.region() != null ? r.region() : "us-central1";
        String uri = r.url().replaceAll("/+$", "") + "/loom/tick";
        long m = r.heartbeatMinutes();
        String schedule = m < 60 ? "*/" + m + " * * * *" : "0 */" + Math.max(1, m / 60) + " * * *";
        List<String> create = new ArrayList<>(List.of("gcloud", "scheduler", "jobs", "create", "http", job(r),
                "--location=" + region, "--schedule=" + schedule, "--uri=" + uri, "--http-method=POST"));
        List<String> notes = new ArrayList<>();
        if (r.serviceAccount() != null) {
            create.add("--oidc-service-account-email=" + r.serviceAccount());
            create.add("--oidc-token-audience=" + r.url().replaceAll("/+$", ""));
        } else {
            create.add("--headers=X-Loom-Token=$LOOM_TRIGGER_TOKEN");
            notes.add("no --service-account: the job sends X-Loom-Token; run this yourself so your shell fills in "
                    + "$LOOM_TRIGGER_TOKEN, and set the same value on the service");
        }
        if (r.mode() == InstallRequest.Mode.EXACT) notes.add("cloud-scheduler uses the heartbeat only");
        return new Plan(name(), List.of(), List.of(), List.of(
                Plan.Command.bestEffort("gcloud", "scheduler", "jobs", "delete", job(r), "--location=" + region, "--quiet"),
                new Plan.Command(create, null, false)), notes);
    }

    @Override
    public Plan uninstall(InstallRequest r, CommandRunner runner) {
        String region = r.region() != null ? r.region() : "us-central1";
        return new Plan(name(), List.of(), List.of(), List.of(
                Plan.Command.of("gcloud", "scheduler", "jobs", "delete", job(r), "--location=" + region, "--quiet")), List.of());
    }
}
