package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.review.cost.Tokens;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Facts read from the text of agents' prompts: whether a variable is written into the system
 * prompt, how large it is, whether it holds something shaped like a secret, and whether it is a
 * near copy of another agent's. They fill fields of the {@code agent} entities, so they run after
 * the agents exist. A prompt file that cannot be read leaves them unknown.
 */
final class PromptFacts implements FactProvider {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z_][A-Za-z0-9_.]*}");

    /**
     * Shapes of keys that must not be written in a script; a plain pattern list, no entropy guess.
     */
    private static final List<Pattern> SECRETS =
            List.of(
                    Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
                    Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
                    Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
                    Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
                    Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    Pattern.compile(
                            "(?i)\\b(api[_-]?key|secret|token|password)\\b\\s*[:=]\\s*[\"']?[A-Za-z0-9/+_-]{16,}"));

    private static final Pattern FORMAT =
            Pattern.compile(
                    "(?i)\\b(json|respond with|reply with|answer with|only one of|one of the following|output format|format:|exactly one word|yes or no|yes/no)\\b");
    private static final Pattern UNSURE =
            Pattern.compile(
                    "(?i)(not sure|unsure|uncertain|if you (cannot|can't|do not know|don't know)|out of scope|do not know|don't know|escalate|ask a (person|human))");
    private static final Pattern EMAIL =
            Pattern.compile(
                    "(?i)\\b[a-z0-9._%+-]+@(?!example\\.(com|org|net)\\b)[a-z0-9.-]+\\.[a-z]{2,}\\b");
    private static final Pattern PHONE =
            Pattern.compile(
                    "(?<!\\d)(\\+\\d{1,3}[ -]?)?\\(?\\d{3}\\)?[ -]\\d{3}[ -]\\d{4}(?!\\d)|\\+\\d{1,3}[ -]?\\d{5}[ -]?\\d{5}(?!\\d)");

    /** The fewest words a prompt needs before it can be called a copy of another. */
    private static final int MIN_WORDS = 20;

    private static final int SHINGLE = 4;
    private static final double SIMILAR = 0.8;

    @Override
    public String name() {
        return "prompt";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        PromptText texts = new PromptText(input.review());
        List<Entity> seen = new ArrayList<>();
        List<Set<String>> shingles = new ArrayList<>();
        for (AgentDef a : input.review().script().getAgents()) {
            Entity e = out.find("agent:" + a.getName()).orElse(null);
            if (e == null) continue;
            Optional<String> text = texts.of(a);
            boolean inline = a.getSystemPrompt() != null && !a.getSystemPrompt().isBlank();
            out.set(
                    e,
                    "systemInterpolates",
                    inline && PLACEHOLDER.matcher(a.getSystemPrompt()).find());
            if (text.isEmpty()) {
                out.set(e, "promptTokens", Unknown.INSTANCE);
                out.set(e, "promptHasSecret", Unknown.INSTANCE);
                out.set(e, "nearDuplicateOf", Unknown.INSTANCE);
                out.set(e, "promptStatesFormat", Unknown.INSTANCE);
                out.set(e, "promptSaysWhenUnsure", Unknown.INSTANCE);
                out.set(e, "promptHasPersonalData", Unknown.INSTANCE);
                continue;
            }
            String t = text.get();
            out.set(e, "promptTokens", Tokens.of(t));
            out.set(e, "promptHasSecret", SECRETS.stream().anyMatch(p -> p.matcher(t).find()));
            out.set(e, "promptStatesFormat", FORMAT.matcher(t).find());
            out.set(e, "promptSaysWhenUnsure", UNSURE.matcher(t).find());
            out.set(e, "promptHasPersonalData", EMAIL.matcher(t).find() || PHONE.matcher(t).find());
            Set<String> mine = shinglesOf(t);
            String twin = "";
            if (wordCount(t) >= MIN_WORDS)
                for (int i = 0; i < seen.size(); i++)
                    if (!shingles.get(i).isEmpty() && jaccard(mine, shingles.get(i)) >= SIMILAR) {
                        twin = (String) seen.get(i).get("name");
                        break;
                    }
            out.set(e, "nearDuplicateOf", twin);
            seen.add(e);
            shingles.add(wordCount(t) >= MIN_WORDS ? mine : Set.of());
        }
    }

    private static int wordCount(String t) {
        String s = t.strip();
        return s.isEmpty() ? 0 : s.split("\\s+").length;
    }

    private static Set<String> shinglesOf(String text) {
        String[] w = text.toLowerCase(java.util.Locale.ROOT).split("\\W+");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + SHINGLE <= w.length; i++)
            out.add(String.join(" ", java.util.Arrays.copyOfRange(w, i, i + SHINGLE)));
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
