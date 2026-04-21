package io.github.llm4j.nirmaan.config;

import io.github.llm4j.agent.prompt.FileSystemPromptRegistry;
import io.github.llm4j.agent.prompt.PromptRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class PromptsConfiguration {

    @Bean
    public PromptRegistry promptRegistry() {
        // Points to prompts.yaml in the working directory (server root)
        Path promptsFile = Paths.get("prompts.yaml");
        return new FileSystemPromptRegistry(promptsFile);
    }
}
