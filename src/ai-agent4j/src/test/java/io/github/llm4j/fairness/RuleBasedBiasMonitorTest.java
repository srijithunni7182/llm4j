package io.github.llm4j.fairness;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuleBasedBiasMonitorTest {

    private final RuleBasedBiasMonitor monitor = new RuleBasedBiasMonitor();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Women are bad at math.|GENDER|HIGH",
            "Immigrants are lazy and take jobs.|NATIONALITY|HIGH",
            "Old people can't learn new technology.|AGE|HIGH",
            "Muslims should not be trusted with this.|RELIGIOUS|HIGH",
            "Poor people are naturally dishonest.|SOCIOECONOMIC|HIGH",
            "Men are always better leaders.|GENDER|MEDIUM",
            "Asians are all good at maths.|RACIAL|MEDIUM",
            "A woman's place is in the home.|GENDER|HIGH",
            "He is too old to learn Python.|AGE|MEDIUM"
    })
    void flagsSweepingStatementsAboutGroups(String text, BiasType type, BiasSeverity severity) {
        List<BiasEvent> events = monitor.detectBias(text, BiasContext.empty());
        assertFalse(events.isEmpty(), text);
        assertTrue(events.stream().anyMatch(e -> e.getType() == type && e.getSeverity() == severity), events.toString());
        assertTrue(events.get(0).getExplanation().contains(events.get(0).getText()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Women founded 30% of the startups in the cohort.",
            "The team includes men and women from twelve countries.",
            "Refugees arriving this year need housing support.",
            "Our seniors discount applies on weekdays.",
            "Is the model too slow to use?",
            ""
    })
    void leavesNeutralTextAlone(String text) {
        assertTrue(monitor.detectBias(text, BiasContext.empty()).isEmpty(), text);
    }

    @Test
    void highFindingsCallForIntervention() {
        assertTrue(monitor.shouldIntervene(monitor.detectBias("Girls are too emotional to lead.")));
        assertFalse(monitor.shouldIntervene(monitor.detectBias("Boomers are always on Facebook.")));
        assertTrue(monitor.detectBias(null, BiasContext.empty()).isEmpty());
    }
}
