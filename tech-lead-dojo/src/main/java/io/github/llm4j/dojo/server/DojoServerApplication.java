package io.github.llm4j.dojo.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "io.github.llm4j.dojo")
public class DojoServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DojoServerApplication.class, args);
    }
}
