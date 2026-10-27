package io.github.llm4j.loom.deploy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Plain Kubernetes manifests: no Helm, no Kustomize, no values in them. */
final class Kube {

    private Kube() {}

    static Map<String, String> render(DeployPlan p, DeployOptions o) {
        String name = Deploy.safeName(o.name());
        Map<String, String> f = new LinkedHashMap<>();
        f.put("k8s/serviceaccount.yaml", serviceAccount(name));
        f.put("k8s/configmap.yaml", configMap(p, o, name));
        f.put("k8s/secret.template.yaml", secret(p, o, name));
        if (o.journal().equals("volume")) f.put("k8s/pvc.yaml", pvc(name));
        f.put("k8s/deployment.yaml", deployment(p, o, name));
        if (o.withFabric()) f.put("k8s/fabric.yaml", fabric(o, name));
        f.put("k8s/networkpolicy.yaml", networkPolicy(p, o, name));
        return f;
    }

    private static String serviceAccount(String name) {
        return "apiVersion: v1\nkind: ServiceAccount\nmetadata:\n  name: "
                + name
                + "\nautomountServiceAccountToken: false\n";
    }

    private static String configMap(DeployPlan p, DeployOptions o, String name) {
        StringBuilder b =
                new StringBuilder(
                        "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: "
                                + name
                                + "-config\ndata:\n");
        b.append("  LOOM_STORE: /data\n");
        if (o.journal().startsWith("jdbc:"))
            b.append("  LOOM_JOURNAL_URL: \"").append(o.journal()).append("\"\n");
        if (o.withFabric())
            b.append("  LOOM_FABRIC_URL: http://")
                    .append(name)
                    .append("-fabric:")
                    .append(Deploy.FABRIC_PORT)
                    .append('\n');
        return b.toString();
    }

    private static String secret(DeployPlan p, DeployOptions o, String name) {
        StringBuilder b =
                new StringBuilder(
                        "# A template: the values are empty on purpose. Fill them with your secret manager, never in git.\n");
        b.append("apiVersion: v1\nkind: Secret\nmetadata:\n  name: ")
                .append(name)
                .append("-secrets\ntype: Opaque\nstringData:\n");
        for (String n : p.envNames()) b.append("  ").append(n).append(": \"\"\n");
        if (o.withFabric()) b.append("  LOOM_FABRIC_CLIENT_KEY: \"\"\n");
        return b.toString();
    }

    private static String pvc(String name) {
        return "apiVersion: v1\nkind: PersistentVolumeClaim\nmetadata:\n  name: "
                + name
                + "-data\nspec:\n  accessModes: [ReadWriteOnce]\n  resources:\n    requests:\n      storage: 1Gi\n";
    }

    private static String context() {
        return "      securityContext:\n        runAsNonRoot: true\n        runAsUser: "
                + Deploy.APP_UID
                + "\n        runAsGroup: "
                + Deploy.APP_UID
                + "\n        fsGroup: "
                + Deploy.APP_UID
                + "\n        seccompProfile:\n          type: RuntimeDefault\n";
    }

    private static String container(String name, String image, String[] args, boolean app) {
        StringBuilder b = new StringBuilder();
        b.append("        - name: ").append(name).append('\n');
        b.append("          image: ").append(image).append('\n');
        if (args != null) {
            b.append("          args: [");
            for (int i = 0; i < args.length; i++)
                b.append(i > 0 ? ", " : "").append('"').append(args[i]).append('"');
            b.append("]\n");
        }
        b.append(
                "          securityContext:\n            allowPrivilegeEscalation: false\n            readOnlyRootFilesystem: true\n            capabilities:\n              drop: [ALL]\n");
        b.append(
                "          resources:\n            limits:\n              memory: 1Gi\n              cpu: \"1\"\n            requests:\n              memory: 256Mi\n              cpu: 100m\n");
        return b.toString();
    }

    private static String deployment(DeployPlan p, DeployOptions o, String name) {
        StringBuilder b = new StringBuilder();
        b.append("apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: ")
                .append(name)
                .append('\n');
        b.append(
                        "spec:\n  replicas: 1\n  strategy:\n    type: Recreate\n  selector:\n    matchLabels:\n      app: ")
                .append(name)
                .append('\n');
        b.append("  template:\n    metadata:\n      labels:\n        app: ")
                .append(name)
                .append('\n');
        b.append("    spec:\n      serviceAccountName: ")
                .append(name)
                .append("\n      automountServiceAccountToken: false\n");
        b.append(context());
        b.append("      containers:\n");
        b.append(
                "      # Set image to your registry's name, ideally name@sha256:<digest>, before production.\n");
        b.append(container(name, name + ":local", null, true));
        b.append("          imagePullPolicy: IfNotPresent\n");
        b.append("          envFrom:\n            - configMapRef:\n                name: ")
                .append(name)
                .append("-config\n");
        b.append("            - secretRef:\n                name: ")
                .append(name)
                .append("-secrets\n");
        b.append(
                "          volumeMounts:\n            - name: tmp\n              mountPath: /tmp\n");
        b.append("            - name: data\n              mountPath: /data\n");
        b.append(
                        "          livenessProbe:\n            exec:\n              command: [\"java\", \"-jar\", \"/app/weave.jar\", \"check\", \"/app/project/")
                .append(p.script())
                .append("\", \"--no-env\"]\n");
        b.append(
                "            initialDelaySeconds: 60\n            periodSeconds: 300\n            timeoutSeconds: 60\n");
        b.append("      volumes:\n        - name: tmp\n          emptyDir: {}\n");
        if (o.journal().equals("volume"))
            b.append(
                            "        - name: data\n          persistentVolumeClaim:\n            claimName: ")
                    .append(name)
                    .append("-data\n");
        else
            // no volume (none, or a database): the root is read-only, so /data is scratch space
            b.append("        - name: data\n          emptyDir: {}\n");
        return b.toString();
    }

    private static String fabric(DeployOptions o, String name) {
        String fab = name + "-fabric";
        StringBuilder b = new StringBuilder();
        // history and client registrations must outlive the pod
        b.append(pvc(fab)).append("---\n");
        b.append("apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: ")
                .append(fab)
                .append('\n');
        b.append(
                        "spec:\n  replicas: 1\n  strategy:\n    type: Recreate\n  selector:\n    matchLabels:\n      app: ")
                .append(fab)
                .append('\n');
        b.append("  template:\n    metadata:\n      labels:\n        app: ")
                .append(fab)
                .append('\n');
        b.append("    spec:\n      automountServiceAccountToken: false\n");
        b.append(context());
        b.append("      containers:\n");
        b.append(
                container(
                        "fabric",
                        o.fabricImage() == null ? DeployOptions.DEFAULT_FABRIC : o.fabricImage(),
                        new String[] {
                            "serve", "--data", "/fabric", "--bind", "0.0.0.0", "--trust-proxy"
                        },
                        false));
        b.append("          ports:\n            - containerPort: ")
                .append(Deploy.FABRIC_PORT)
                .append('\n');
        b.append(
                "          volumeMounts:\n            - name: tmp\n              mountPath: /tmp\n            - name: fabric\n              mountPath: /fabric\n");
        b.append(
                        "          readinessProbe:\n            httpGet:\n              path: /api/session\n              port: ")
                .append(Deploy.FABRIC_PORT)
                .append('\n');
        b.append(
                "      volumes:\n        - name: tmp\n          emptyDir: {}\n        - name: fabric\n          persistentVolumeClaim:\n            claimName: ");
        b.append(fab).append("-data\n");
        b.append("---\napiVersion: v1\nkind: Service\nmetadata:\n  name: ")
                .append(fab)
                .append("\nspec:\n  type: ClusterIP\n  selector:\n    app: ")
                .append(fab)
                .append("\n  ports:\n    - port: ")
                .append(Deploy.FABRIC_PORT)
                .append("\n      targetPort: ")
                .append(Deploy.FABRIC_PORT)
                .append('\n');
        return b.toString();
    }

    /**
     * A NetworkPolicy names pods, namespaces and address ranges, not host names. The app may reach
     * DNS, Fabric, and (only when the script uses a hosted model) port 443 outside the cluster's
     * private ranges; the hosts the script talks to are listed in the annotation for an egress
     * proxy or a policy engine that can name them.
     */
    private static String networkPolicy(DeployPlan p, DeployOptions o, String name) {
        StringBuilder b = new StringBuilder();
        b.append("apiVersion: networking.k8s.io/v1\nkind: NetworkPolicy\nmetadata:\n  name: ")
                .append(name)
                .append("-egress\n");
        b.append("  annotations:\n    loom.io/egress-hosts: \"")
                .append(String.join(",", p.egressHosts()))
                .append("\"\n");
        b.append("spec:\n  podSelector:\n    matchLabels:\n      app: ").append(name).append('\n');
        b.append("  policyTypes: [Ingress, Egress]\n  ingress: []\n  egress:\n");
        b.append(
                "    - to:\n        - namespaceSelector: {}\n          podSelector:\n            matchLabels:\n              k8s-app: kube-dns\n      ports:\n        - {protocol: UDP, port: 53}\n        - {protocol: TCP, port: 53}\n");
        if (o.withFabric())
            b.append(
                            "    - to:\n        - podSelector:\n            matchLabels:\n              app: ")
                    .append(name)
                    .append("-fabric\n      ports:\n        - {protocol: TCP, port: ")
                    .append(Deploy.FABRIC_PORT)
                    .append("}\n");
        if (!p.egressHosts().isEmpty())
            b.append(
                    "    - to:\n        - ipBlock:\n            cidr: 0.0.0.0/0\n            except: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16]\n      ports:\n        - {protocol: TCP, port: 443}\n");
        return b.toString();
    }
}
