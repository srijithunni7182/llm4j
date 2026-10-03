package io.github.llm4j.evalreport.analysis;

import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.model.RunMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Baseline selection (spec 03 §6): previous run on the same branch by default. */
public final class Baselines {

    public record Pick(RunMeta run, String label) {}

    private Baselines() {}

    /**
     * @param explicit a run id chosen by the user, or null
     * @return the pick, or null when there is no eligible run
     */
    public static Pick select(
            RunMeta candidate, List<RunMeta> store, ReportConfig config, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            for (RunMeta r : store) {
                if (explicit.equals(r.runId())) {
                    return new Pick(r, "Chosen baseline");
                }
            }
            throw new IllegalArgumentException("no run with id " + explicit + " in the store");
        }
        List<RunMeta> eligible = new ArrayList<>();
        for (RunMeta r : store) {
            boolean sameProject = Objects.equals(r.project(), candidate.project());
            boolean earlier =
                    r.startedAt() != null
                            && candidate.startedAt() != null
                            && r.startedAt().compareTo(candidate.startedAt()) < 0;
            boolean done = "COMPLETE".equals(r.status()) || "PARTIAL".equals(r.status());
            if (sameProject && earlier && done && !Objects.equals(r.runId(), candidate.runId())) {
                eligible.add(r);
            }
        }
        eligible.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        String policy = config.baselinePolicy;
        if (policy.startsWith("pinned:")) {
            return select(candidate, store, config, policy.substring("pinned:".length()));
        }
        String defBranch = defaultBranch(config, store);
        if ("main".equals(policy)) {
            return onBranch(eligible, defBranch, "Latest run on " + defBranch);
        }
        if ("lastFull".equals(policy)) {
            for (RunMeta r : eligible) {
                if ("FULL".equals(r.profile().path("name").asText())) {
                    return new Pick(r, "Latest full run");
                }
            }
            return null;
        }
        if (candidate.branch() != null) {
            Pick same =
                    onBranch(
                            eligible,
                            candidate.branch(),
                            "Previous run on branch " + candidate.branch());
            if (same != null) {
                return same;
            }
        }
        Pick fallback =
                onBranch(
                        eligible,
                        defBranch,
                        "Baseline from "
                                + defBranch
                                + ": no earlier run on "
                                + (candidate.branch() == null
                                        ? "this branch"
                                        : "branch " + candidate.branch()));
        return fallback;
    }

    private static Pick onBranch(List<RunMeta> eligible, String branch, String label) {
        if (branch == null) {
            return null;
        }
        for (RunMeta r : eligible) {
            if (branch.equals(r.branch())) {
                return new Pick(r, label);
            }
        }
        return null;
    }

    private static String defaultBranch(ReportConfig config, List<RunMeta> store) {
        if (config.defaultBranch != null) {
            return config.defaultBranch;
        }
        for (RunMeta r : store) {
            if ("main".equals(r.branch())) {
                return "main";
            }
        }
        for (RunMeta r : store) {
            if ("master".equals(r.branch())) {
                return "master";
            }
        }
        return "main";
    }
}
