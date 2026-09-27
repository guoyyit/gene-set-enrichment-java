package com.github.guoyyit.genesetenrichmentjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side limits for enrichment jobs.
 */
@ConfigurationProperties(prefix = "enrichment")
public class EnrichmentProperties {

    private final Executor executor = new Executor();
    private String storeDir = "data/jobs";
    private long defaultTimeoutMs = 30_000;

    public Executor getExecutor() {
        return executor;
    }

    public String getStoreDir() {
        return storeDir;
    }

    public void setStoreDir(String storeDir) {
        this.storeDir = storeDir;
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public static class Executor {
        private int coreSize = 4;
        private int maxSize = 8;
        private int queueCapacity = 100;

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
