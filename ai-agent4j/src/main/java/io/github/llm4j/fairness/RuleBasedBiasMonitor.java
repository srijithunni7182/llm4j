package io.github.llm4j.fairness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fast, deterministic bias check: flags sweeping statements about groups of people — "women are bad
 * at…", "immigrants can't…", "old people are always…". No model call, so it is free and repeatable; it
 * reads words, not meaning, so it can flag a sentence that quotes a stereotype in order to refute it.
 * Use {@link LLMBiasMonitor} where that nuance matters.
 *
 * <ul>
 *   <li><b>HIGH</b>: a group said to be deficient ("are lazy", "are bad at", "are inferior") or
 *       barred ("can't", "shouldn't", "don't belong").
 *   <li><b>MEDIUM</b>: an absolute generalisation ("are always", "are naturally", "are all"), or an age
 *       disqualifier ("too old to learn").
 * </ul>
 */
public class RuleBasedBiasMonitor implements BiasMonitor {

    private static final Map<BiasType, String> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put(BiasType.GENDER,
                "women|men|girls|boys|females|males|ladies|gentlemen|wives|husbands|mothers|fathers");
        GROUPS.put(BiasType.AGE,
                "old people|older people|the elderly|elderly people|seniors|boomers|young people|millennials|teenagers|gen z");
        GROUPS.put(BiasType.RACIAL,
                "black people|white people|blacks|whites|asians|africans|hispanics|latinos|latinas|arabs");
        GROUPS.put(BiasType.RELIGIOUS,
                "muslims|christians|hindus|jews|sikhs|buddhists|atheists");
        GROUPS.put(BiasType.NATIONALITY,
                "immigrants|foreigners|refugees|migrants|indians|chinese|americans|mexicans|russians|pakistanis|nigerians");
        GROUPS.put(BiasType.SOCIOECONOMIC,
                "poor people|the poor|rich people|the rich|homeless people|working-class people");
    }

    private static final String DEFICIENT =
            "lazy|stupid|dumb|unintelligent|inferior|weak|irrational|hysterical|violent|dangerous|criminals|"
                    + "greedy|dishonest|untrustworthy|incapable|useless|worthless|dirty|terrorists|"
                    + "bad at|worse at|terrible at|not good at|not smart|not capable|too emotional|unfit";
    private static final String BARRED =
            "can't|cannot|can not|shouldn't|should not|should never|must not|don't belong|do not belong|"
                    + "aren't capable|are not capable|are incapable";
    private static final String ABSOLUTE = "always|never|naturally|inherently|by nature|all";

    private record Rule(BiasType type, BiasSeverity severity, Pattern pattern, String why) {}

    private final List<Rule> rules = new ArrayList<>();

    public RuleBasedBiasMonitor() {
        for (Map.Entry<BiasType, String> g : GROUPS.entrySet()) {
            String group = "\\b(?:all |most |every )?(" + g.getValue() + ")\\b";
            rules.add(new Rule(g.getKey(), BiasSeverity.HIGH,
                    compile(group + "\\s+(?:are|is)\\s+(?:(?:all|always|naturally|inherently|just|simply|so|too|generally)\\s+)?(?:" + DEFICIENT + ")\\b"),
                    "calls a whole group deficient"));
            rules.add(new Rule(g.getKey(), BiasSeverity.HIGH,
                    compile(group + "\\s+(?:" + BARRED + ")\\b"),
                    "says what a whole group can't or shouldn't do"));
            rules.add(new Rule(g.getKey(), BiasSeverity.MEDIUM,
                    compile(group + "\\s+are\\s+(?:" + ABSOLUTE + ")\\b"),
                    "generalises about a whole group"));
        }
        rules.add(new Rule(BiasType.GENDER, BiasSeverity.HIGH,
                compile("\\b(?:a woman's place is|women belong in the kitchen|(?:women|girls) belong at home)\\b"),
                "prescribes a gender role"));
        rules.add(new Rule(BiasType.AGE, BiasSeverity.MEDIUM,
                compile("\\btoo old to (?:learn|work|change|understand|adapt|use|be hired)\\b"),
                "treats age as disqualifying"));
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    @Override
    public List<BiasEvent> detectBias(String text, BiasContext context) {
        List<BiasEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) return events;
        for (Rule rule : rules) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                String matched = m.group();
                String group = m.groupCount() >= 1 && m.group(1) != null ? m.group(1).toLowerCase(Locale.ROOT) : null;
                events.add(BiasEvent.builder()
                        .type(rule.type())
                        .severity(rule.severity())
                        .text(matched)
                        .explanation("\"" + matched + "\" " + rule.why() + (group != null ? " (" + group + ")" : ""))
                        .confidence(rule.severity() == BiasSeverity.HIGH ? 0.8 : 0.6)
                        .addMetadata("rule", rule.why())
                        .build());
            }
        }
        return events;
    }
}
