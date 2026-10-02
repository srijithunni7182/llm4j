package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.KnowledgeDef;
import io.github.llm4j.loom.ast.LoomScript;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A policy replay: the file the candidate's agent reads (a skill, a knowledge file) is replaced by another, for the candidate only, so a rule change
 * can be tried on past cases without editing the script. The replacement lives in a scratch directory; nothing the run owns is touched.
 */
public final class PolicyOverlay {

    /** The directory to resolve the candidate's files against, and the hashes of the file before and after. */
    public record Prepared(Path baseDir, Map<String, String> hashes) { }

    private PolicyOverlay() { }

    /** The files an agent reads that are local (not a URL or a classpath resource), as written in the script. */
    static Set<String> references(LoomScript script, AgentDef agent) {
        Set<String> out = new LinkedHashSet<>();
        for (String skill : agent.getSkills()) {
            String ref = skill.startsWith("fs://") ? skill.substring(5) : skill;
            if (!ref.contains("://")) out.add(ref);
        }
        for (String name : agent.getKnowledgeBases()) {
            for (KnowledgeDef k : script.getKnowledgeBases()) {
                if (k.getName().equals(name) && k.getPath() != null && !k.getPath().contains("://")) out.add(k.getPath());
            }
        }
        return out;
    }

    public static Prepared prepare(LoomScript script, DecisionDef decision, Path baseDir, Path policy, Path workDir) {
        AgentDef agent = script.getAgents().stream().filter(a -> a.getName().equals(decision.getAgent())).findFirst().orElseThrow();
        Set<String> refs = references(script, agent);
        String wanted = policy.getFileName().toString();
        String match = refs.stream().filter(r -> Path.of(r).getFileName().toString().equals(wanted)).findFirst().orElse(null);
        if (match == null) {
            throw new IllegalArgumentException("--policy " + policy + ": agent " + agent.getName() + " reads no file named " + wanted
                    + (refs.isEmpty() ? " (it reads no local files)" : " (it reads: " + String.join(", ", refs) + ")"));
        }
        try {
            Files.createDirectories(workDir);
            Map<String, String> hashes = new LinkedHashMap<>();
            for (String ref : refs) {
                Path from = baseDir.resolve(ref).normalize();
                Path to = workDir.resolve(ref).normalize();
                if (!to.startsWith(workDir.normalize())) throw new IllegalArgumentException("the file " + ref + " is outside the script's directory");
                if (ref.equals(match)) {
                    Files.createDirectories(to.getParent());
                    hashes.put(ref + " (before)", Files.exists(from) ? hash(Files.readAllBytes(from)) : "missing");
                    Files.copy(policy, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    hashes.put(ref + " (replacement)", hash(Files.readAllBytes(policy)));
                } else if (Files.isDirectory(from)) {
                    try (Stream<Path> walk = Files.walk(from)) {
                        for (Path f : walk.toList()) {
                            Path target = to.resolve(from.relativize(f).toString());
                            if (Files.isDirectory(f)) Files.createDirectories(target);
                            else {
                                Files.createDirectories(target.getParent());
                                Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                } else if (Files.exists(from)) {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new Prepared(workDir, hashes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
