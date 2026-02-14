package io.github.llm4j.dojo.server.controller;

import io.github.llm4j.dojo.engine.DojoOrchestrator;
import io.github.llm4j.dojo.engine.StakeholderFactory;
import io.github.llm4j.dojo.engine.SystemGenerator;
import io.github.llm4j.dojo.model.DojoOption;
import io.github.llm4j.dojo.model.ProjectState;
import io.github.llm4j.dojo.model.StakeholderProfile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DojoConfig {

    @Bean
    public DojoOrchestrator dojoOrchestrator() {
        StakeholderFactory stakeholderFactory = new StakeholderFactory();
        List<StakeholderProfile> stakeholders = stakeholderFactory.createStakeholdersForSystem();
        SystemGenerator systemGenerator = new SystemGenerator(); // Mock LLM for now
        return new DojoOrchestrator(systemGenerator, stakeholders);
    }
}
