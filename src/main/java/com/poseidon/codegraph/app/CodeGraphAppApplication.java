package com.poseidon.codegraph.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runnable Code Graph application.
 */
@SpringBootApplication
@EnableScheduling
public class CodeGraphAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeGraphAppApplication.class, args);
    }
}
