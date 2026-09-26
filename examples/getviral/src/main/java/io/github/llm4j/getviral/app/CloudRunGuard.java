package io.github.llm4j.getviral.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a Cloud Run revision with settings that only make sense on a laptop. Cloud Run
 * sets K_SERVICE; locally nothing here applies, so {@code ./launch.sh} keeps working with zero setup.
 */
@Component
public class CloudRunGuard {

    private final AppProperties props;
    private final Map<String, String> env;

    @Autowired
    public CloudRunGuard(AppProperties props) {
        this(props, System.getenv());
    }

    CloudRunGuard(AppProperties props, Map<String, String> env) {
        this.props = props;
        this.env = env;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void check() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe configuration for Cloud Run (K_SERVICE=" + env.get("K_SERVICE")
                    + "):\n  - " + String.join("\n  - ", problems) + "\nSee examples/getviral/DEPLOY.md.");
        }
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (blank(env.get("K_SERVICE"))) return problems;
        if (props.auth().devLogin()) {
            problems.add("GETVIRAL_DEV_LOGIN=true lets anyone sign in as any email — never enable it on a public server");
        }
        if (!props.auth().googleConfigured()) {
            problems.add("GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are required for sign-in");
        }
        if (blank(props.tokenKey())) {
            problems.add("GETVIRAL_TOKEN_KEY is required — every instance must share one key to decrypt social tokens");
        }
        if (!props.media().gcsConfigured()) {
            problems.add("GETVIRAL_GCS_BUCKET is required — instance disks are temporary, generated media would vanish");
        }
        if (props.publicUrl() == null || !props.publicUrl().startsWith("https://")) {
            problems.add("GETVIRAL_PUBLIC_URL must be the service's https:// URL (OAuth redirects are built from it)");
        }
        String db = env.getOrDefault("GETVIRAL_DB_URL", "");
        if (!db.startsWith("jdbc:postgresql:")) {
            problems.add("GETVIRAL_DB_URL must point at Cloud SQL for PostgreSQL — the embedded H2 file is per-instance");
        }
        return problems;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
