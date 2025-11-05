package com.recurra.service;

import com.recurra.config.RecurraProperties;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Service for forwarding requests to AI providers.
 */
@Slf4j
@Service
public class ProviderService {

    private final WebClient webClient;
    private final RecurraProperties properties;

    public ProviderService(WebClient webClient, RecurraProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /**
     * Forward request to the appropriate provider.
     */
    public Mono<ChatCompletionResponse> forward(ChatCompletionRequest request) {
        // Determine provider from model name
        String provider = determineProvider(request.getModel());

        RecurraProperties.ProviderConfig config = properties.getProviders().get(provider);
        if (config == null || !config.isEnabled()) {
            return Mono.error(new RuntimeException("Provider not configured or disabled: " + provider));
        }

        log.info("Forwarding request to provider: {} (model: {})", provider, request.getModel());

        return webClient.post()
                .uri(config.getBaseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .retryWhen(Retry.backoff(properties.getProxy().getMaxRetries(), Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10))
                        .filter(throwable -> isRetryable(throwable)))
                .doOnSuccess(response -> log.info("Received response from provider: {}", provider))
                .doOnError(error -> log.error("Error forwarding to provider: {}", provider, error));
    }

    /**
     * Determine which provider to use based on the model name.
     */
    private String determineProvider(String model) {
        if (model == null) {
            return "openai"; // Default
        }

        model = model.toLowerCase();

        if (model.startsWith("gpt-") || model.startsWith("text-") || model.startsWith("davinci")) {
            return "openai";
        } else if (model.startsWith("claude")) {
            return "anthropic";
        }

        // Default to OpenAI
        return "openai";
    }

    /**
     * Check if an error is retryable.
     */
    private boolean isRetryable(Throwable throwable) {
        // Retry on network errors and 5xx server errors
        String message = throwable.getMessage();
        if (message == null) {
            return false;
        }

        return message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("500") ||
                message.contains("502") ||
                message.contains("503") ||
                message.contains("504");
    }
}
