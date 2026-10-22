package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R37: the security accounting is bounded: 20,000 tool results, with findings and refusals mixed
 * in, stay within a time budget and keep the view small.
 */
class SecuritySoakTest {

    @Test
    void twentyThousandToolResultsStayBounded() {
        SecurityFixtures f = new SecurityFixtures();
        long start = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            String agent = "Agent" + (i % 5);
            switch (i % 4) {
                case 0 -> f.tool(i * 0.01, agent, "Fetch", "untrusted");
                case 1 -> f.suspect(i * 0.01, agent, "Fetch", "untrusted", "override", 1);
                case 2 -> f.failed(
                        i * 0.01, agent, "DB" + (i % 7), "private", "permission_denied", false);
                default -> f.tool(i * 0.01, agent, "Send", "outward");
            }
        }
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(took).isLessThan(Duration.ofSeconds(20));
        Map<String, Object> view = f.security();
        assertThat(view.toString().length())
                .as("the section stays small however long the run is")
                .isLessThan(20_000);
        assertThat(f.state.view().toString().length()).isLessThan(2_000_000);
        assertThat(
                        f.incidents("TRIFECTA").size()
                                + f.incidents("INJECTION").size()
                                + f.incidents("ACCESS").size())
                .isLessThan(100);
    }
}
