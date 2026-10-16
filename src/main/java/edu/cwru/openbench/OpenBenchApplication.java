package edu.cwru.openbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** OpenBench: one Spring Boot process serving the REST API under {@code /api} and the web client. */
@SpringBootApplication
public class OpenBenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenBenchApplication.class, args);
    }
}
