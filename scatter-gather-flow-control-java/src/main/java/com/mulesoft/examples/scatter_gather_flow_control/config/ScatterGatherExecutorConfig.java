package com.mulesoft.examples.scatter_gather_flow_control.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Declares the bounded executor on which the scatter-gather routes run in parallel (D-263).
 *
 * <p>Source: {@code scatter-gather-flow-control/src/main/app/scatter-gather.xml:8}, the
 * {@code <scatter-gather>} element. {@code service.ScatterGatherService} injects this bean as
 * {@code @Qualifier("scatterGatherExecutor") Executor} and submits route {@code sourceA} (index 0)
 * and route {@code sourceB} (index 1) to it with {@code CompletableFuture.supplyAsync} (D-263).
 */
@Configuration
public class ScatterGatherExecutorConfig {

    /**
     * Builds the {@code scatterGatherExecutor} bean: two core threads, at most two threads, a task
     * queue of 100 and thread names starting with {@code scatter-gather-}. The rejected-execution
     * handler is the default abort policy: a task submitted while both threads are busy and 100 tasks
     * are queued raises {@link org.springframework.core.task.TaskRejectedException}. The application
     * context starts the executor with {@code afterPropertiesSet()} and stops it with
     * {@code destroy()} (D-263).
     *
     * @return the configured, not yet initialised executor
     */
    @Bean(name = "scatterGatherExecutor")
    public ThreadPoolTaskExecutor scatterGatherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("scatter-gather-");
        return executor;
    }
}
