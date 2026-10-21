package io.github.llm4j.loom.inspect.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A heuristic look at what an untrusted tool returned, for text that reads like an instruction to
 * the agent: "ignore all previous instructions", a line that opens as {@code system:}, a request to
 * send what was seen to an address, a request for the system prompt or a key, characters that hide
 * text.
 *
 * <p>It is a <b>lead for a person to follow, not a verdict</b>: a careful attack can pass it, and a
 * harmless page can trip it. It says which <i>groups</i> of pattern matched and how many times,
 * never what matched, so the answer is safe to send wherever the run's records go.
 *
 * <p>Bounded in every way: it reads the first {@link #LIMIT} characters; every pattern has bounded
 * repetition (no nested or open-ended quantifier, so no catastrophic backtracking); it stops
 * counting at {@link #MAX_COUNT}; and it is deterministic. The list of patterns is fixed here on
 * purpose (see the Phase 4 design, decision 8).
 */
public final class InjectionScan {

    /** How much of a result is read. */
    public static final int LIMIT = 20_000;

    /** The most matches counted. */
    public static final int MAX_COUNT = 50;

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Map<String, Pattern> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put(
                "override",
                Pattern.compile(
                        "\\b(?:ignore|disregard|forget|override)\\b[^.\\n]{0,40}?\\b(?:previous|prior|above|earlier|preceding|all|your|any)\\b[^.\\n]{0,40}?\\b(?:instructions?|prompts?|rules?|directions?|guidelines?)\\b",
                        FLAGS));
        GROUPS.put(
                "role",
                Pattern.compile(
                        "^[ \\t]*(?:system|assistant|developer)[ \\t]*:|<\\|im_start\\|>|<\\|(?:system|assistant)\\|>|\\[/?INST\\]|<<SYS>>|###[ \\t]*(?:system|instructions?)\\b",
                        FLAGS | Pattern.MULTILINE));
        GROUPS.put(
                "exfil",
                Pattern.compile(
                        "\\b(?:send|email|mail|forward|upload|exfiltrate|leak|transmit)\\b[^.\\n]{0,60}?\\b(?:to|at)\\b[^\\n]{0,30}?(?:https?://|[\\w.+-]{1,64}@[\\w-]{1,63}\\.[\\w.-]{1,63})",
                        FLAGS));
        GROUPS.put(
                "reveal",
                Pattern.compile(
                        "\\b(?:reveal|print|show|repeat|output|display|tell me|give me)\\b[^.\\n]{0,40}?\\b(?:system prompt|your (?:hidden )?instructions|hidden instructions|api[ _-]?keys?|secret keys?|private keys?|credentials)\\b",
                        FLAGS));
        GROUPS.put(
                "hidden",
                Pattern.compile(
                        "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]|<!--[^>]{0,200}?\\b(?:ignore|instructions?|system prompt|disregard)\\b",
                        FLAGS));
    }

    private InjectionScan() {}

    /**
     * What the scan found.
     *
     * @param groups the names of the groups that matched, sorted ({@code exfil}, {@code hidden},
     *     {@code override}, {@code reveal}, {@code role})
     * @param count how many matches in all, at most {@link #MAX_COUNT}
     */
    public record Result(List<String> groups, int count) {
        public Result {
            groups = List.copyOf(groups);
        }

        public boolean suspect() {
            return !groups.isEmpty();
        }
    }

    public static Result scan(String text) {
        if (text == null || text.isEmpty()) return new Result(List.of(), 0);
        String head = text.length() > LIMIT ? text.substring(0, LIMIT) : text;
        TreeSet<String> found = new TreeSet<>();
        int count = 0;
        for (Map.Entry<String, Pattern> g : GROUPS.entrySet()) {
            Matcher m = g.getValue().matcher(head);
            while (count < MAX_COUNT && m.find()) {
                found.add(g.getKey());
                count++;
            }
        }
        return new Result(new ArrayList<>(found), count);
    }
}
