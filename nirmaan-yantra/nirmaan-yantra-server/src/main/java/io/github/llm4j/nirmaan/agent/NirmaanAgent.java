package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;

public interface NirmaanAgent {
    String getName();

    String getRole();

    void execute(ProjectContext context);
}
