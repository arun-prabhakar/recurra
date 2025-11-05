package com.recurra.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration for Caffeine.
 */
@Configuration
public class CacheConfiguration {

    private final RecurraProperties properties;

    public CacheConfiguration(RecurraProperties properties) {
        this.properties = properties;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("responses");
        cacheManager.setCaffeine(caffeineCacheBuilder());
        return cacheManager;
    }

    private Caffeine<Object, Object> caffeineCacheBuilder() {
        return Caffeine.newBuilder()
                .maximumSize(properties.getCache().getMaxSize())
                .expireAfterWrite(properties.getCache().getExpireAfterWrite())
                .recordStats();
    }
}
