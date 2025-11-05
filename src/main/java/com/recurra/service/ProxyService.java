package com.recurra.service;

import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Main proxy service that orchestrates cache lookup and provider forwarding.
 */
@Slf4j
@Service
public class ProxyService {

    private final TemplateCacheService cacheService;
    private final ProviderService providerService;

    public ProxyService(TemplateCacheService cacheService, ProviderService providerService) {
        this.cacheService = cacheService;
        this.providerService = providerService;
    }

    /**
     * Process a chat completion request.
     * First checks cache, then forwards to provider if needed.
     */
    public Mono<ChatCompletionResponse> processRequest(ChatCompletionRequest request) {
        log.debug("Processing request for model: {}", request.getModel());

        // Check cache first
        Optional<ChatCompletionResponse> cached = cacheService.get(request);
        if (cached.isPresent()) {
            log.info("Serving cached response");
            return Mono.just(cached.get());
        }

        // Cache miss - forward to provider
        log.info("Cache miss - forwarding to provider");
        return providerService.forward(request)
                .doOnSuccess(response -> {
                    // Cache the response
                    cacheService.put(request, response);
                    log.info("Response cached successfully");
                });
    }

    /**
     * Clear the cache.
     */
    public void clearCache() {
        cacheService.clear();
    }

    /**
     * Get cache statistics.
     */
    public TemplateCacheService.CacheStats getCacheStats() {
        return cacheService.getStats();
    }
}
