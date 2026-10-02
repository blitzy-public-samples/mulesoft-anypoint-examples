package com.mulesoft.examples.cache_scope_with_fibonacci;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/** Starts the cache-scope-with-fibonacci application with Spring caching enabled. */
@SpringBootApplication
@EnableCaching
public class CacheScopeWithFibonacciApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheScopeWithFibonacciApplication.class, args);
    }
}
