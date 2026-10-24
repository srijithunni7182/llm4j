package io.github.llm4j.loom.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R22 to R28, R33 of spec 15: what a deployment says, derived from the script and nothing else. */
public class DeployTest {

    @TempDir Path dir;

    public static final String SCRIPT =
            """
            tool Hook { use: webhook  url: env.HOOK_URL }
            agent Writer { model: "gemini-2.5-flash"  system: "You write." }
            agent Checker { model: "claude-sonnet-4-5"  system: "You check." }
            intentclassifier Intent { model: "gpt-4.1-mini"  choose kind: a | b }
            workflow Main(q) {
                delegate "{q}" to Writer -> r
                human_prompt "ok?" -> ok
                return r
            }
            workflow Daily(q) { delegate "{q}" to Writer -> r  note "{r}" }
            schedule Morning { cron: "0 7 * * *"  run: Daily(q = "x") }
            """;

    static DeployOptions options(String target) {
        return new DeployOptions(
                target,
                "triage",
                null,
                !target.equals("docker"),
                null,
                "volume",
                "none",
                true,
                "127.0.0.1",
                "No findings were open.");
    }

    private DeployPlan plan(String script) throws Exception {
        Path p = dir.resolve("main.loom");
        Files.writeString(p, script);
        return DeployPlan.derive(new LoomLoader().load(p.toString()), "main.loom");
    }

    @Test
    void thePlanIsReadFromTheScript() throws Exception {
        DeployPlan p = plan(SCRIPT);
        assertThat(p.envNames()).contains("GEMINI_API_KEY", "ANTHROPIC_API_KEY", "HOOK_URL");
        assertThat(p.egressHosts())
                .contains("generativelanguage.googleapis.com", "api.anthropic.com");
        assertThat(p.schedules()).containsExactly("Morning");
        assertThat(p.asksAPerson()).isTrue();
        assertThat(p.workflows()).containsExactly("Main", "Daily");
        assertThat(p.envNames()).isSorted();
    }

    @Test
    void aLocalModelNeedsNoKeyAndNoFixedHost() throws Exception {
        DeployPlan p =
                plan(
                        "agent A { model: \"ollama/llama3\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n");
        assertThat(p.envNames()).isEmpty();
        assertThat(p.egressHosts()).isEmpty();
        assertThat(p.localModels()).isTrue();
    }

    @Test
    void theDockerfileIsNonRootPinnedOrSaysSoAndHasAHealthcheck() throws Exception {
        Map<String, String> f = Deploy.render(plan(SCRIPT), options("docker"), "abc");
        String d = f.get("Dockerfile");
        assertThat(d)
                .contains("USER 10001:10001")
                .contains("HEALTHCHECK")
                .contains("FROM eclipse-temurin:17-jre")
                .contains("pin it (tag@sha256");
        assertThat(d).doesNotContain("--privileged").doesNotContain("USER root");
        assertThat(f.keySet())
                .containsExactly(
                        "Dockerfile",
                        ".dockerignore",
                        "entrypoint.sh",
                        ".env.example",
                        "README.md");
        DeployOptions pinned =
                new DeployOptions(
                        "docker",
                        "triage",
                        "eclipse-temurin:17-jre@sha256:" + "a".repeat(64),
                        false,
                        null,
                        "volume",
                        "none",
                        true,
                        "127.0.0.1",
                        null);
        assertThat(Deploy.render(plan(SCRIPT), pinned, "abc").get("Dockerfile"))
                .contains("pinned by digest")
                .doesNotContain("pin it");
    }

    @Test
    void theEntrypointRunsTheDaemonForSchedulesAndReportsToFabricWhenToldTo() throws Exception {
        Map<String, String> f = Deploy.render(plan(SCRIPT), options("compose"), "abc");
        assertThat(f.get("entrypoint.sh"))
                .startsWith("#!/bin/sh")
                .contains("daemon /data/triggers")
                .contains("--observe-fabric \"$LOOM_FABRIC_URL\" --observe-name triage")
                .contains("/run/secrets");
        DeployOptions noSched =
                new DeployOptions(
                        "docker",
                        "triage",
                        null,
                        false,
                        null,
                        "volume",
                        "none",
                        false,
                        "127.0.0.1",
                        null);
        assertThat(Deploy.render(plan(SCRIPT), noSched, "abc").get("entrypoint.sh"))
                .contains("run /app/project/main.loom")
                .doesNotContain("daemon")
                .doesNotContain("--observe-fabric");
    }

    @Test
    void composeHasTheSafetyPropertiesAndFabricOnAnInternalNetwork() throws Exception {
        String c = Deploy.render(plan(SCRIPT), options("compose"), "abc").get("compose.yaml");
        assertThat(c)
                .contains("read_only: true")
                .contains("cap_drop:\n      - ALL")
                .contains("no-new-privileges:true")
                .contains("user: \"10001:10001\"");
        assertThat(c)
                .contains("internal: true")
                .contains("127.0.0.1:9090:9090")
                .contains("condition: service_healthy");
        assertThat(c)
                .contains("      - GEMINI_API_KEY\n")
                .contains("      - HOOK_URL\n")
                .contains("LOOM_FABRIC_URL=http://fabric:9090");
        assertThat(c).contains("data:/data").contains("loom_fabric_client_key");
        assertThat(c)
                .doesNotContain("privileged")
                .doesNotContain("0.0.0.0:")
                .doesNotContain("network_mode: host");
    }

    @Test
    void anAppThatListensIsPublishedOnLoopbackOnly() throws Exception {
        DeployOptions o =
                new DeployOptions(
                        "compose",
                        "triage",
                        null,
                        true,
                        null,
                        "volume",
                        "http:8080",
                        true,
                        "127.0.0.1",
                        null);
        assertThat(Deploy.render(plan(SCRIPT), o, "abc").get("compose.yaml"))
                .contains("127.0.0.1:8080:8080");
    }

    @Test
    void exposingFabricBeyondLoopbackIsRefused() throws Exception {
        for (String addr : new String[] {"0.0.0.0", "192.168.1.5", "::"}) {
            DeployOptions o =
                    new DeployOptions(
                            "compose", "triage", null, true, null, "volume", "none", true, addr,
                            null);
            assertThatThrownBy(() -> Deploy.render(plan(SCRIPT), o, "abc"))
                    .isInstanceOf(Deploy.Refused.class)
                    .hasMessageContaining("TLS");
        }
        DeployOptions bad =
                new DeployOptions(
                        "compose",
                        "triage",
                        null,
                        true,
                        null,
                        "volume",
                        "tcp:80",
                        true,
                        "127.0.0.1",
                        null);
        assertThatThrownBy(() -> Deploy.render(plan(SCRIPT), bad, "abc"))
                .hasMessageContaining("--expose");
    }

    @Test
    void stateGoesOnAVolumeUnlessToldOtherwise() throws Exception {
        assertThat(Deploy.render(plan(SCRIPT), options("compose"), "abc").get("Dockerfile"))
                .contains("VOLUME /data");
        DeployOptions none =
                new DeployOptions(
                        "compose",
                        "triage",
                        null,
                        true,
                        null,
                        "none",
                        "none",
                        true,
                        "127.0.0.1",
                        null);
        Map<String, String> f = Deploy.render(plan(SCRIPT), none, "abc");
        assertThat(f.get("Dockerfile")).doesNotContain("VOLUME");
        assertThat(f.get("compose.yaml")).doesNotContain("data:/data");
        assertThat(f.get("README.md")).contains("no journal is kept");
        DeployOptions jdbc =
                new DeployOptions(
                        "compose",
                        "triage",
                        null,
                        true,
                        null,
                        "jdbc:postgresql://db/loom",
                        "none",
                        true,
                        "127.0.0.1",
                        null);
        assertThat(Deploy.render(plan(SCRIPT), jdbc, "abc").get("compose.yaml"))
                .contains("LOOM_JOURNAL_URL=jdbc:postgresql://db/loom");
    }

    @Test
    void kubernetesManifestsAreNonRootReadOnlyAndEmptySecretsAreTemplates() throws Exception {
        Map<String, String> f = Deploy.render(plan(SCRIPT), options("k8s"), "abc");
        assertThat(f.keySet())
                .contains(
                        "k8s/deployment.yaml",
                        "k8s/fabric.yaml",
                        "k8s/networkpolicy.yaml",
                        "k8s/secret.template.yaml",
                        "k8s/pvc.yaml",
                        "k8s/serviceaccount.yaml",
                        "k8s/configmap.yaml");
        String d = f.get("k8s/deployment.yaml");
        assertThat(d)
                .contains("runAsNonRoot: true")
                .contains("readOnlyRootFilesystem: true")
                .contains("drop: [ALL]")
                .contains("RuntimeDefault")
                .contains("allowPrivilegeEscalation: false")
                .contains("automountServiceAccountToken: false");
        assertThat(f.get("k8s/secret.template.yaml"))
                .contains("GEMINI_API_KEY: \"\"")
                .contains("LOOM_FABRIC_CLIENT_KEY: \"\"");
        assertThat(f.get("k8s/fabric.yaml"))
                .contains("type: ClusterIP")
                .doesNotContain("LoadBalancer")
                .doesNotContain("NodePort");
    }

    @Test
    void theEgressPolicyFollowsTheScript() throws Exception {
        String hosted =
                Deploy.render(plan(SCRIPT), options("k8s"), "abc").get("k8s/networkpolicy.yaml");
        assertThat(hosted)
                .contains("loom.io/egress-hosts: \"api.anthropic.com,")
                .contains("port: 443")
                .contains("except: [10.0.0.0/8")
                .contains("k8s-app: kube-dns")
                .contains("app: triage-fabric");
        DeployPlan local =
                plan(
                        "agent A { model: \"ollama/llama3\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n");
        String none = Deploy.render(local, options("k8s"), "abc").get("k8s/networkpolicy.yaml");
        assertThat(none).doesNotContain("port: 443").contains("egress-hosts: \"\"");
    }

    @Test
    void theSameInputGivesTheSameBytesAndTheStampSaysWhereItCameFrom() throws Exception {
        Map<String, String> a = Deploy.render(plan(SCRIPT), options("k8s"), "abc");
        Map<String, String> b = Deploy.render(plan(SCRIPT), options("k8s"), "abc");
        assertThat(a).isEqualTo(b);
        assertThat(a.get("Dockerfile")).contains("weave deploy 1 from main.loom sha256:abc body ");
        a.values()
                .forEach(
                        v ->
                                assertThat(v)
                                        .doesNotContain("Instant")
                                        .doesNotContainPattern("20[0-9]{2}-[0-9]{2}-[0-9]{2}T"));
    }

    @Test
    void anEditedFileIsRecognisedByItsBodyHash() throws Exception {
        String d = Deploy.render(plan(SCRIPT), options("docker"), "abc").get("Dockerfile");
        assertThat(Deploy.edited(d)).isFalse();
        assertThat(Deploy.edited(d + "# mine\n")).isTrue();
        assertThat(Deploy.edited("no stamp here")).isFalse();
        String e = Deploy.render(plan(SCRIPT), options("docker"), "abc").get("entrypoint.sh");
        assertThat(e).startsWith("#!/bin/sh\n# weave deploy");
        assertThat(Deploy.edited(e)).isFalse();
        assertThat(Deploy.edited(e.replace("set -eu", "set -eux"))).isTrue();
        assertThat(
                        Deploy.edited(
                                Deploy.render(plan(SCRIPT), options("docker"), "abc")
                                        .get("README.md")))
                .isFalse();
    }

    @Test
    void noSecretShapedValueFromTheEnvironmentOrTheScriptReachesAnyFile() throws Exception {
        // the values are built from pieces, so this source holds nothing that looks like a key
        String fakeKey = "sk-" + "proj-" + "A".repeat(30) + "Zz09";
        String fakeUrl = "https://hooks.slack.com/services/" + "T000" + "/B000/" + "X".repeat(24);
        System.setProperty("GEMINI_API_KEY", fakeKey);
        System.setProperty("HOOK_URL", fakeUrl);
        for (String target : new String[] {"docker", "compose", "k8s"}) {
            Map<String, String> f = Deploy.render(plan(SCRIPT), options(target), "abc");
            f.forEach(
                    (name, text) ->
                            assertThat(text)
                                    .as(name)
                                    .doesNotContain(fakeKey)
                                    .doesNotContain(fakeUrl)
                                    .doesNotContain("T000/B000"));
        }
        System.clearProperty("GEMINI_API_KEY");
        System.clearProperty("HOOK_URL");
    }

    @Test
    void theReadmeSaysWhatItNeedsWhatReviewSaidAndWhatEachFailureDoes() throws Exception {
        String r = Deploy.render(plan(SCRIPT), options("compose"), "abc").get("README.md");
        assertThat(r)
                .contains("`GEMINI_API_KEY`")
                .contains("deploy/weave.jar")
                .contains("No findings were open.")
                .contains("**Restart**")
                .contains("**Volume loss**")
                .contains("Schedules (Morning)")
                .contains("never blocks a deployment")
                .contains("a run waiting for a person stays waiting");
    }

    @Test
    void thePackagesAreIndependentOfDockerKubernetesAndTheNetwork() throws Exception {
        for (String pkg : new String[] {"deploy", "bind"}) {
            Path root = Path.of("src/main/java/io/github/llm4j/loom/" + pkg);
            try (var s = Files.walk(root)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(p);
                    assertThat(text)
                            .as(p.toString())
                            .doesNotContain("java.net.")
                            .doesNotContain("ProcessBuilder")
                            .doesNotContain("Runtime.getRuntime")
                            .doesNotContain("io.github.llm4j.loom.cli.");
                }
            }
        }
    }
}
