package io.github.llm4j.gmail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// We skip the actual agent test in this simplistic environment because we don't want to actually connect to Gmail/Filesystem during CI
// effectively just testing context loading and basic bean wiring
@SpringBootTest
@TestPropertySource(properties = {
        "GMAIL_USER=test@example.com",
        "GMAIL_APP_PASSWORD=test_password",
        "GOOGLE_API_KEY=test_api_key"
})
public class GmailMcpIntegrationTest {

    @Autowired
    private GmailAgentService agentService;

    @Test
    void contextLoads() {
        // Simple sanity check that the Spring context starts up correctly
    }
}
