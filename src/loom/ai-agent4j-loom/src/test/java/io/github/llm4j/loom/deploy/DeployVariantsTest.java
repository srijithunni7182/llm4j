package io.github.llm4j.loom.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R26, R27, R28 of spec 15: each file says what it must, and each option changes only what it
 * should.
 */
class DeployVariantsTest {

    @TempDir Path dir;

    private DeployPlan plan() throws Exception {
        Path p = dir.resolve("main.loom");
        Files.writeString(p, DeployTest.SCRIPT);
        return DeployPlan.derive(new LoomLoader().load(p.toString()), "main.loom");
    }

    private Map<String, String> render(
            String target, boolean fabric, String journal, String expose, String fabricImage)
            throws Exception {
        return Deploy.render(
                plan(),
                new DeployOptions(
                        target,
                        "My App!",
                        null,
                        fabric,
                        fabricImage,
                        journal,
                        expose,
                        true,
                        "127.0.0.1",
                        "S"),
                "abc");
    }

    @Test
    void theNameIsMadeSafeForAFileAnImageAndALabel() throws Exception {
        Map<String, String> f = render("k8s", true, "volume", "none", null);
        assertThat(f.get("k8s/deployment.yaml"))
                .contains("name: my-app\n")
                .contains("app: my-app")
                .contains("claimName: my-app-data");
        assertThat(Deploy.safeName("")).isEqualTo("loom-app");
        assertThat(Deploy.safeName("--A__b--")).isEqualTo("a-b");
        assertThat(Deploy.safeName("x".repeat(80))).hasSize(40);
    }

    @Test
    void everyKubernetesFileSaysWhatItIsFor() throws Exception {
        Map<String, String> f = render("k8s", true, "volume", "none", "registry.example/fabric:1");
        assertThat(f.get("k8s/serviceaccount.yaml"))
                .contains("kind: ServiceAccount")
                .contains("name: my-app")
                .contains("automountServiceAccountToken: false");
        assertThat(f.get("k8s/configmap.yaml"))
                .contains("kind: ConfigMap")
                .contains("LOOM_STORE: /data")
                .contains("LOOM_FABRIC_URL: http://my-app-fabric:9090");
        assertThat(f.get("k8s/secret.template.yaml"))
                .contains("kind: Secret")
                .contains("A template: the values are empty on purpose")
                .contains("name: my-app-secrets");
        assertThat(f.get("k8s/pvc.yaml"))
                .contains("kind: PersistentVolumeClaim")
                .contains("name: my-app-data")
                .contains("ReadWriteOnce")
                .contains("storage: 1Gi");
        String d = f.get("k8s/deployment.yaml");
        assertThat(d)
                .contains("kind: Deployment")
                .contains("type: Recreate")
                .contains("replicas: 1")
                .contains("name: my-app-config")
                .contains("name: my-app-secrets")
                .contains("mountPath: /data")
                .contains("mountPath: /tmp")
                .contains("livenessProbe")
                .contains("/app/project/main.loom")
                .contains("memory: 1Gi");
        String fab = f.get("k8s/fabric.yaml");
        assertThat(fab)
                .contains("image: registry.example/fabric:1")
                .contains("name: my-app-fabric")
                .contains("containerPort: 9090")
                .contains("readinessProbe")
                .contains("kind: Service")
                .contains("targetPort: 9090")
                .contains("\"--trust-proxy\"")
                .contains("readOnlyRootFilesystem: true");
        assertThat(f.get("k8s/networkpolicy.yaml"))
                .contains("kind: NetworkPolicy")
                .contains("policyTypes: [Ingress, Egress]")
                .contains("ingress: []")
                .contains("name: my-app-egress")
                .contains("port: 9090");
    }

    @Test
    void withoutFabricThereIsNoFabricAnywhere() throws Exception {
        for (String target : new String[] {"k8s", "compose"}) {
            Map<String, String> f = render(target, false, "volume", "none", null);
            f.forEach(
                    (name, text) ->
                            assertThat(text)
                                    .as(target + " " + name)
                                    .doesNotContain("--trust-proxy")
                                    .doesNotContain("LOOM_FABRIC_CLIENT_KEY: \"\"")
                                    .doesNotContain("loom_fabric_client_key"));
        }
        assertThat(render("k8s", false, "volume", "none", null))
                .doesNotContainKey("k8s/fabric.yaml");
        assertThat(render("k8s", false, "volume", "none", null).get("k8s/configmap.yaml"))
                .doesNotContain("LOOM_FABRIC_URL");
        assertThat(render("compose", false, "volume", "none", null).get("compose.yaml"))
                .doesNotContain("fabric:")
                .doesNotContain("internal");
        assertThat(render("compose", false, "volume", "none", null).get("entrypoint.sh"))
                .doesNotContain("observe-fabric");
    }

    @Test
    void noJournalMeansNoVolumeNoClaimAndNoMount() throws Exception {
        Map<String, String> f = render("k8s", true, "none", "none", null);
        assertThat(f).doesNotContainKey("k8s/pvc.yaml");
        assertThat(f.get("k8s/deployment.yaml"))
                .doesNotContain("claimName")
                .doesNotContain("mountPath: /data");
        Map<String, String> c = render("compose", false, "none", "none", null);
        assertThat(c.get("compose.yaml")).doesNotContain("volumes:");
    }

    @Test
    void anAppThatListensIsExposedInTheImageAndPublishedOnLoopback() throws Exception {
        Map<String, String> f = render("compose", true, "volume", "http:8081", null);
        assertThat(f.get("Dockerfile")).contains("EXPOSE 8081");
        assertThat(f.get("compose.yaml")).contains("\"127.0.0.1:8081:8081\"");
        assertThat(render("compose", true, "volume", "none", null).get("Dockerfile"))
                .doesNotContain("EXPOSE");
    }

    @Test
    void theDockerignoreKeepsSecretsAndHistoryOutOfTheImage() throws Exception {
        String ignore = render("docker", false, "volume", "none", null).get(".dockerignore");
        assertThat(ignore)
                .contains(".git\n")
                .contains(".env\n")
                .contains("!.env.example")
                .contains("deploy/")
                .contains("loom-inspect-outbox/");
    }

    @Test
    void theEnvTemplateHasNamesOnly() throws Exception {
        String env = render("compose", true, "volume", "none", null).get(".env.example");
        assertThat(env)
                .contains("GEMINI_API_KEY=<your value>")
                .contains("LOOM_FABRIC_URL=http://fabric:9090")
                .contains("LOOM_FABRIC_CLIENT_KEY=<");
        assertThat(render("docker", false, "volume", "none", null).get(".env.example"))
                .doesNotContain("LOOM_FABRIC");
    }

    @Test
    void theHealthcheckAndEntrypointNameTheScript() throws Exception {
        Map<String, String> f = render("docker", false, "volume", "none", null);
        assertThat(f.get("Dockerfile"))
                .contains("check /app/project/main.loom --no-env")
                .contains("--interval=5m")
                .contains("ENTRYPOINT [\"/app/entrypoint.sh\"]")
                .contains("ENV LOOM_STORE=/data")
                .contains("chmod 0555");
        assertThat(f.get("entrypoint.sh")).contains("mkdir -p /data/triggers");
    }
}
