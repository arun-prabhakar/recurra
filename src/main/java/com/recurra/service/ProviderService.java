package com.recurra.service;

import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import com.recurra.provider.ChatProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Service for forwarding requests to AI providers using the provider adapter pattern.
 * Automatically routes requests to the appropriate provider based on model name.
 */
@Slf4j
@Service
public class ProviderService {

    private final List<ChatProvider> providers;

    public ProviderService(List<ChatProvider> providers) {
        this.providers = providers;
        log.info("Initialized ProviderService with {} providers: {}",
                providers.size(),
                providers.stream().map(ChatProvider::getName).toList());
    }

    /**
     * Forward request to the appropriate provider based on model name.
     * Uses the first provider that supports the model and is enabled.
     */
    public Mono<ChatCompletionResponse> forward(ChatCompletionRequest request) {
        String model = request.getModel();

        // Find the first provider that supports this model
        ChatProvider provider = providers.stream()
                .filter(ChatProvider::isEnabled)
                .filter(p -> p.supports(model))
                .findFirst()
                .orElse(null);

        if (provider == null) {
            log.error("No enabled provider found for model: {}", model);
            return Mono.error(new RuntimeException(
                    "No provider available for model: " + model +
                    ". Enabled providers: " +
                    providers.stream()
                            .filter(ChatProvider::isEnabled)
                            .map(ChatProvider::getName)
                            .toList()
            ));
        }

        log.info("Routing model '{}' to provider '{}'", model, provider.getName());

        return provider.complete(request)
                .doOnSuccess(response -> log.info("Successfully received response from provider: {}",
                        provider.getName()))
                .doOnError(error -> log.error("Error from provider {}: {}",
                        provider.getName(), error.getMessage()));
    }

    /**
     * Get list of available providers.
     */
    public List<ChatProvider> getProviders() {
        return providers;
    }

    /**
     * Get a specific provider by name.
     */
    public ChatProvider getProvider(String name) {
        return providers.stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
