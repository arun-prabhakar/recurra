package com.recurra.service;

import com.recurra.config.RecurraProperties;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Template-aware cache service that stores and retrieves responses
 * based on structural similarity rather than exact matches.
 */
@Slf4j
@Service
public class TemplateCacheService {

    private final TemplateExtractor templateExtractor;
    private final RecurraProperties properties;

    // Cache storage: exactKey -> response
    private final Map<String, CacheEntry> exactCache = new ConcurrentHashMap<>();

    // Template index: templateKey -> list of exactKeys
    private final Map<String, ConcurrentHashMap<String, ChatCompletionRequest>> templateIndex = new ConcurrentHashMap<>();

    public TemplateCacheService(TemplateExtractor templateExtractor, RecurraProperties properties) {
        this.templateExtractor = templateExtractor;
        this.properties = properties;
    }

    /**
     * Get cached response if available.
     * First tries exact match, then template-based match if enabled.
     */
    public Optional<ChatCompletionResponse> get(ChatCompletionRequest request) {
        if (!properties.getCache().isEnabled()) {
            return Optional.empty();
        }

        // Try exact match first
        String exactKey = templateExtractor.generateExactKey(request);
        if (exactKey != null) {
            CacheEntry entry = exactCache.get(exactKey);
            if (entry != null && !entry.isExpired()) {
                log.debug("Cache HIT (exact): {}", exactKey);
                return Optional.of(markAsCached(entry.response));
            }
        }

        // Try template-based match if enabled
        if (properties.getCache().isTemplateMatching()) {
            Optional<ChatCompletionResponse> templateMatch = findTemplateMatch(request);
            if (templateMatch.isPresent()) {
                log.debug("Cache HIT (template): {}", exactKey);
                return templateMatch.map(this::markAsCached);
            }
        }

        log.debug("Cache MISS: {}", exactKey);
        return Optional.empty();
    }

    /**
     * Store response in cache.
     */
    public void put(ChatCompletionRequest request, ChatCompletionResponse response) {
        if (!properties.getCache().isEnabled()) {
            return;
        }

        String exactKey = templateExtractor.generateExactKey(request);
        if (exactKey == null) {
            log.warn("Failed to generate cache key for request");
            return;
        }

        // Store in exact cache
        CacheEntry entry = new CacheEntry(response, System.currentTimeMillis());
        exactCache.put(exactKey, entry);

        // Index by template if template matching is enabled
        if (properties.getCache().isTemplateMatching()) {
            String templateKey = templateExtractor.generateTemplateKey(request);
            if (templateKey != null) {
                templateIndex
                        .computeIfAbsent(templateKey, k -> new ConcurrentHashMap<>())
                        .put(exactKey, request);
            }
        }

        log.debug("Cached response: {}", exactKey);
    }

    /**
     * Find a template-based match.
     */
    private Optional<ChatCompletionResponse> findTemplateMatch(ChatCompletionRequest request) {
        String templateKey = templateExtractor.generateTemplateKey(request);
        if (templateKey == null) {
            return Optional.empty();
        }

        // Check if we have any requests with the same template
        Map<String, ChatCompletionRequest> candidates = templateIndex.get(templateKey);
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        // Find the best matching request
        double threshold = properties.getCache().getSimilarityThreshold();
        String bestMatch = null;
        double bestSimilarity = 0.0;

        for (Map.Entry<String, ChatCompletionRequest> candidate : candidates.entrySet()) {
            double similarity = templateExtractor.calculateSimilarity(request, candidate.getValue());

            if (similarity >= threshold && similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = candidate.getKey();
            }
        }

        if (bestMatch != null) {
            CacheEntry entry = exactCache.get(bestMatch);
            if (entry != null && !entry.isExpired()) {
                log.info("Template match found with similarity: {}", bestSimilarity);
                return Optional.of(entry.response);
            }
        }

        return Optional.empty();
    }

    /**
     * Mark response as cached by setting the x_cached flag.
     */
    private ChatCompletionResponse markAsCached(ChatCompletionResponse response) {
        return ChatCompletionResponse.builder()
                .id(response.getId())
                .object(response.getObject())
                .created(response.getCreated())
                .model(response.getModel())
                .choices(response.getChoices())
                .usage(response.getUsage())
                .systemFingerprint(response.getSystemFingerprint())
                .cached(true)
                .build();
    }

    /**
     * Clear all cache entries.
     */
    public void clear() {
        exactCache.clear();
        templateIndex.clear();
        log.info("Cache cleared");
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        return new CacheStats(
                exactCache.size(),
                templateIndex.size()
        );
    }

    /**
     * Cache entry with expiration.
     */
    private class CacheEntry {
        private final ChatCompletionResponse response;
        private final long timestamp;

        public CacheEntry(ChatCompletionResponse response, long timestamp) {
            this.response = response;
            this.timestamp = timestamp;
        }

        public boolean isExpired() {
            long expiryMillis = properties.getCache().getExpireAfterWrite().toMillis();
            return System.currentTimeMillis() - timestamp > expiryMillis;
        }
    }

    /**
     * Cache statistics.
     */
    public record CacheStats(int exactEntries, int templateKeys) {
    }
}
