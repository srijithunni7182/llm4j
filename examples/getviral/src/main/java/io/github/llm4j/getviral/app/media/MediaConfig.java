package io.github.llm4j.getviral.app.media;

import io.github.llm4j.getviral.app.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MediaConfig {

    @Bean
    MediaStore mediaStore(AppProperties props) {
        return props.media().gcsConfigured() ? new GcsMediaStore(props.media().gcsBucket()) : new LocalMediaStore();
    }
}
