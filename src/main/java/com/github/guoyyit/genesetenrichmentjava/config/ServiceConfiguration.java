package com.github.guoyyit.genesetenrichmentjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genesetenrichmentjava.core.EnrichmentService;
import com.github.guoyyit.genesetenrichmentjava.store.FileResultStore;
import com.github.guoyyit.genesetenrichmentjava.store.ResultStore;
import com.github.guoyyit.genesetenrichmentjava.task.TaskManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the service beans (task manager, store, enrichment service).
 */
@Configuration
public class ServiceConfiguration {

    @Bean
    public EnrichmentService enrichmentService() {
        return new EnrichmentService();
    }

    @Bean
    public ResultStore resultStore(@Value("${enrichment.data-dir:./data/results}") String dataDir,
                                   ObjectMapper objectMapper) {
        return new FileResultStore(Path.of(dataDir), objectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    public TaskManager taskManager(EnrichmentService enrichmentService, ResultStore resultStore,
                                   @Value("${enrichment.max-concurrent:4}") int maxConcurrent,
                                   @Value("${enrichment.max-queue:100}") int maxQueue) {
        return new TaskManager(enrichmentService, resultStore, maxConcurrent, maxQueue);
    }
}
