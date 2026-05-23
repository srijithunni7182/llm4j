package io.github.llm4j.tantrik.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures CORS for all /api/** endpoints.
 * Allowed origins are read from {@code tantrik.console.cors.origins} (comma-separated).
 * Requirement 8.2
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(
            @Value("${tantrik.console.cors.origins:http://localhost:5173,http://localhost:3000}")
            String originsProperty) {
        this.allowedOrigins = originsProperty.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
