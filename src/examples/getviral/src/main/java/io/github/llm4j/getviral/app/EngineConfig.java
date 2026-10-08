package io.github.llm4j.getviral.app;

import io.github.llm4j.getviral.app.media.MediaStore;
import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.engine.GetViralEngine;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import io.github.llm4j.getviral.app.memory.JdbcCastingHistory;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Configuration;

/** One shared GetViral engine; each run gets its own agents, tools, memory and media folder. */
@Configuration
public class EngineConfig {

    @Bean
    GetViralConfig getViralConfig(AppProperties props) {
        return GetViralConfig.fromEnvironment().withDataDir(Path.of(props.dataDir()));
    }

    @Bean
    GetViralEngine getViralEngine(GetViralConfig config, MediaStore mediaStore, JdbcTemplate jdbc,
                                  @Value("${getviral.demo-pace-ms:350}") long demoPaceMillis) {
        long pace = config.mode() == GetViralConfig.Mode.DEMO ? demoPaceMillis : 0;
        GetViralEngine engine = new GetViralEngine(config, pace);
        engine.mediaSink(mediaStore::put);
        engine.castingHistory(new JdbcCastingHistory(jdbc));
        return engine;
    }
}
