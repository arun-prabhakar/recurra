package com.recurra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Main application class for Recurra - OpenAI-compatible proxy with template-aware caching.
 */
@SpringBootApplication
@EnableCaching
public class RecurraApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecurraApplication.class, args);
    }
}
