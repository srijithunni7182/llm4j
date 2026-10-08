package io.github.llm4j.multiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Multi-Agent Collaboration Platform Application.
 */
@SpringBootApplication(scanBasePackages = "io.github.llm4j")
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages = "io.github.llm4j")
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = "io.github.llm4j")
public class MultiAgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiAgentPlatformApplication.class, args);
    }
}
