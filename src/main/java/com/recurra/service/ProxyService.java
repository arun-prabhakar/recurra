package com.recurra.service;

import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Main proxy service that orchestrates cache lookup and provider forwarding.
 * Now uses AdvancedCacheService (Redis + Postgres with template matching).
 */
@Slf4j
@Service
public class ProxyService {

    private final AdvancedCacheService advancedCacheService;
    private final ProviderService providerService;

    public ProxyService(AdvancedCacheService advancedCacheService, ProviderService providerService) {
        this.advancedCacheService = advancedCacheService;
        this.providerService = providerService;
    }

    /**
     * Process a chat completion request.
     * First checks cache (exact + template), then forwards to provider if needed.
     */
    public Mono<ProxyResponse> processRequest(ChatCompletionRequest request) {
        log.debug("Processing request for model: {}", request.getModel());

        // Check cache first (exact + template matching)
        Optional<AdvancedCacheService.CacheResult> cacheResult = advancedCacheService.get(request);

        if (cacheResult.isPresent()) {
            AdvancedCacheService.CacheResult result = cacheResult.get();
            log.info("Serving {} cached response (score={}, latency={}ms)",
                    result.getMatchType(), result.getScore(), result.getLatencyMs());

            return Mono.just(ProxyResponse.builder()
                    .response(markAsCached(result.getResponse()))
                    .cacheHit(true)
                    .matchType(result.getMatchType().name().toLowerCase())
                    .score(result.getScore())
                    .provenanceId(result.getProvenanceId())
                    .sourceModel(result.getSourceModel())
                    .build());
        }

        // Cache miss - forward to provider
        log.info("Cache miss - forwarding to provider");
        return providerService.forward(request)
                .map(response -> {
                    // Cache the response (async)
                    advancedCacheService.put(request, response);
                    log.info("Response cached successfully");

                    return ProxyResponse.builder()
                            .response(response)
                            .cacheHit(false)
                            .matchType("none")
                            .score(0.0)
                            .build();
                });
    }

    /**
     * Mark response as cached.
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
     * Clear the cache.
     */
    public void clearCache() {
        advancedCacheService.clear();
    }

    /**
     * Get cache statistics.
     */
    public AdvancedCacheService.CacheStats getCacheStats() {
        return advancedCacheService.getStats();
    }

    /**
     * Proxy response with cache metadata.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProxyResponse {
        private ChatCompletionResponse response;
        private boolean cacheHit;
        private String matchType;  // "exact", "template", "none"
        private double score;
        private String provenanceId;
        private String sourceModel;
    }
}
