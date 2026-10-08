package io.github.llm4j.getviral.app;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Clean URLs for the three pages; every page loads its data from the authenticated API. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/landing.html");
        registry.addViewController("/welcome").setViewName("forward:/onboarding.html");
        registry.addViewController("/studio").setViewName("forward:/app.html");
        registry.addViewController("/library").setViewName("forward:/app.html");
    }
}
