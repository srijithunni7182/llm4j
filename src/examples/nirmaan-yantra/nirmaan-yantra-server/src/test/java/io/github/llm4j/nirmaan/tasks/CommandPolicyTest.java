package io.github.llm4j.nirmaan.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.nirmaan.tasks.CommandPolicy.Decision;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** The table that reviews the rule: what the factory runs on its own, what it asks a person about, and what it refuses. */
class CommandPolicyTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '#', textBlock = """
            mvn test                                   # APPROVED
            mvn clean package -DskipTests              # APPROVED
            npm install && npm run build               # APPROVED
            pip install -r requirements.txt            # APPROVED
            pytest                                     # APPROVED
            python3 -m pytest e2e-tests                # APPROVED
            go build -o app .                          # APPROVED
            bash e2e-tests/run.sh                      # APPROVED
            ./gradlew test                             # APPROVED
            npm test -- --grep "adds two numbers"      # APPROVED
            rm -rf /tmp/x                              # BLOCKED
            sudo mvn test                              # BLOCKED
            mvn test && rm -rf target                  # BLOCKED
            /bin/rm -rf x                              # BLOCKED
            mvn test; rm -rf x                         # NEEDS_REVIEW
            mvn test | tee out.log                     # NEEDS_REVIEW
            mvn test > out.log                         # NEEDS_REVIEW
            echo $(whoami)                             # NEEDS_REVIEW
            curl http://example.com                    # NEEDS_REVIEW
            bash /etc/passwd                           # NEEDS_REVIEW
            bash ../outside.sh                         # NEEDS_REVIEW
            bash -c "mvn test"                         # NEEDS_REVIEW
            mvn -f /etc/pom.xml test                   # NEEDS_REVIEW
            mvn test &&                                # NEEDS_REVIEW
            nice -n 5 mvn test                         # NEEDS_REVIEW
            """)
    void classifiesCommands(String command, Decision expected) {
        assertThat(CommandPolicy.classify(command).decision()).as(command).isEqualTo(expected);
    }

    @Test
    void anApprovedCommandIsSplitIntoArgumentsWithNoShellInvolved() {
        var result = CommandPolicy.classify("npm install && npm test -- --grep \"two words\"");

        assertThat(result.parts()).containsExactly(List.of("npm", "install"), List.of("npm", "test", "--", "--grep", "two words"));
    }

    @Test
    void anEmptyOrBrokenCommandIsNotRunOnItsOwn() {
        assertThat(CommandPolicy.classify("").decision()).isEqualTo(Decision.NEEDS_REVIEW);
        assertThat(CommandPolicy.classify("mvn \"test").decision()).isEqualTo(Decision.NEEDS_REVIEW);
    }
}
