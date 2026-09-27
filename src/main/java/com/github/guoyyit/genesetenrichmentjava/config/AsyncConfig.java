package com.github.guoyyit.genesetenrichmentjava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for enrichment jobs. When the queue is full new
 * submissions are rejected (AbortPolicy) and reported as SERVICE_BUSY
 * instead of blocking web threads or slowing unrelated requests.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "enrichmentExecutor")
    public ThreadPoolTaskExecutor enrichmentExecutor(EnrichmentProperties properties) {
        EnrichmentProperties.Executor cfg = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.getCoreSize());
        executor.setMaxPoolSize(cfg.getMaxSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setThreadNamePrefix("enrichment-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
