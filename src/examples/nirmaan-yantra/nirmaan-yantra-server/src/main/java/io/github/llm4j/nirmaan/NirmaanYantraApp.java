package io.github.llm4j.nirmaan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class NirmaanYantraApp {

    public static void main(String[] args) {
        SpringApplication.run(NirmaanYantraApp.class, args);
    }

}
