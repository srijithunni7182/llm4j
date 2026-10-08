package io.github.llm4j.getviral.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The hosted, multi-user GetViral web app (Spring Boot). The CLI still runs without Spring. */
@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, io.github.llm4j.getviral.app.connect.ConnectorProperties.class})
@EnableScheduling
public class GetViralWebApp { }
