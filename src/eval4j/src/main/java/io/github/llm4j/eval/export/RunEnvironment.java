package io.github.llm4j.eval.export;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Facts about where a run happened: git, CI, JVM. Best effort; never throws. */
final class RunEnvironment {

    private RunEnvironment() {}

    static Map<String, Object> runtime() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("javaVersion", System.getProperty("java.version"));
        env.put("os", System.getProperty("os.name"));
        return env;
    }

    static Map<String, Object> source() {
        Map<String, Object> s = new LinkedHashMap<>();
        String branch =
                firstNonBlank(
                        System.getenv("BRANCH_NAME"),
                        System.getenv("GITHUB_HEAD_REF"),
                        System.getenv("GITHUB_REF_NAME"),
                        System.getenv("CI_COMMIT_REF_NAME"),
                        git("rev-parse", "--abbrev-ref", "HEAD"));
        if (branch != null && !branch.equals("HEAD")) {
            s.put("branch", branch);
        }
        String commit =
                firstNonBlank(
                        System.getenv("GIT_COMMIT"),
                        System.getenv("GITHUB_SHA"),
                        System.getenv("CI_COMMIT_SHA"),
                        git("rev-parse", "HEAD"));
        if (commit != null && commit.matches("[0-9a-fA-F]{7,64}")) {
            s.put("commit", commit.length() > 12 ? commit.substring(0, 12) : commit);
        }
        String ci = null;
        String number = null;
        if (System.getenv("JENKINS_URL") != null) {
            ci = "jenkins";
            number = System.getenv("BUILD_NUMBER");
        } else if (System.getenv("GITHUB_ACTIONS") != null) {
            ci = "github-actions";
            number = System.getenv("GITHUB_RUN_NUMBER");
        } else if (System.getenv("GITLAB_CI") != null) {
            ci = "gitlab-ci";
            number = System.getenv("CI_PIPELINE_IID");
        }
        if (ci != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("provider", ci);
            if (number != null) {
                c.put("buildNumber", number);
            }
            s.put("ci", c);
        }
        return s;
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String git(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            try (BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                return line == null || line.isBlank() ? null : line.trim();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
