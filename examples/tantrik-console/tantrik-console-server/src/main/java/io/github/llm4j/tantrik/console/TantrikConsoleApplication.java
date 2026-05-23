package io.github.llm4j.tantrik.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TantrikConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(TantrikConsoleApplication.class, args);
    }
}
