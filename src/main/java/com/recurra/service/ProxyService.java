package com.recurra.service;

import com.recurra.model.CacheControlContext;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
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
     * Process a chat completion request with default cache control context.
     */
    public Mono<ProxyResponse> processRequest(ChatCompletionRequest request) {
        return processRequest(request, CacheControlContext.defaults());
    }

    /**
     * Process a chat completion request with cache control context.
     * First checks cache (exact + template), then forwards to provider if needed.
     */
    public Mono<ProxyResponse> processRequest(ChatCompletionRequest request, CacheControlContext context) {
        log.debug("Processing request for model: {} with cache context: bypass={}, store={}, mode={}",
                request.getModel(), context.isBypass(), context.isStore(), context.getMode());

        // Check cache first (exact + template matching)
        Optional<AdvancedCacheService.CacheResult> cacheResult = advancedCacheService.get(request, context);

        if (cacheResult.isPresent()) {
            AdvancedCacheService.CacheResult result = cacheResult.get();
            log.info("Serving {} cached response (score={}, latency={}ms)",
                    result.getMatchType(), result.getScore(), result.getLatencyMs());

            // Calculate cache age
            Long cacheAgeSeconds = null;
            if (result.getCreatedAt() != null) {
                cacheAgeSeconds = Instant.now().getEpochSecond() - result.getCreatedAt().getEpochSecond();
            }

            return Mono.just(ProxyResponse.builder()
                    .response(markAsCached(result.getResponse()))
                    .cacheHit(true)
                    .matchType(result.getMatchType().name().toLowerCase())
                    .score(result.getScore())
                    .provenanceId(result.getProvenanceId())
                    .sourceModel(result.getSourceModel())
                    .cacheAgeSeconds(cacheAgeSeconds)
                    .degraded(false)
                    .build());
        }

        // Cache miss - forward to provider
        log.info("Cache miss - forwarding to provider");
        return providerService.forward(request)
                .map(response -> {
                    // Cache the response (async)
                    advancedCacheService.put(request, response, context);
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
        private Long cacheAgeSeconds;  // Age of cached entry in seconds
        private boolean degraded;  // Whether response is degraded (model family mismatch, etc.)
        private String degradedReason;  // Reason for degradation if applicable
    }
}
