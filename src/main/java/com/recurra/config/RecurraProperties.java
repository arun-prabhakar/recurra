package com.recurra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for Recurra.
 */
@Data
@Component
@ConfigurationProperties(prefix = "recurra")
public class RecurraProperties {

    private Map<String, ProviderConfig> providers = new HashMap<>();
    private CacheConfig cache = new CacheConfig();
    private ProxyConfig proxy = new ProxyConfig();

    @Data
    public static class ProviderConfig {
        private boolean enabled = true;
        private String baseUrl;
        private String apiKey;
    }

    @Data
    public static class CacheConfig {
        private boolean enabled = true;
        private int maxSize = 10000;
        private Duration expireAfterWrite = Duration.ofHours(24);
        private double similarityThreshold = 0.85;
        private boolean templateMatching = true;
    }

    @Data
    public static class ProxyConfig {
        private Duration timeout = Duration.ofSeconds(60);
        private int maxRetries = 3;
    }
}
