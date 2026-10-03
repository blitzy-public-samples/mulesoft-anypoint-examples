package com.mulesoft.examples.legacy_modernization;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Starts the legacy-modernization application.
 *
 * <p>Enables {@code @Async} method execution and declares the two beans the shipping-order file
 * write uses: the {@code legacyFulfillmentExecutor} pool that runs it and the {@link Clock} that
 * stamps its file name (D-060).
 */
@SpringBootApplication
@EnableAsync
public class LegacyModernizationApplication {

    /**
     * Starts the legacy-modernization application.
     *
     * @param args command-line arguments passed to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(LegacyModernizationApplication.class, args);
    }

    /**
     * Executor for the asynchronous shipping-order file write (D-060).
     *
     * <p>Keeps one core thread, grows to at most 16 threads, holds no task in a queue and stops an
     * idle non-core thread after 60 seconds. A task submitted while all 16 threads are busy runs on
     * the submitting thread ({@link ThreadPoolExecutor.CallerRunsPolicy}, D-313). Threads are named
     * {@code legacy-fulfillment-<n>}. The container initializes the executor on creation and shuts
     * it down with the context.
     *
     * @return the un-initialized executor bean named {@code legacyFulfillmentExecutor}
     */
    @Bean("legacyFulfillmentExecutor")
    public ThreadPoolTaskExecutor legacyFulfillmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("legacy-fulfillment-");
        return executor;
    }

    /**
     * Clock in the system default time zone; it supplies the time in the shipping-order file name
     * (D-313).
     *
     * @return {@link Clock#systemDefaultZone()}
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
