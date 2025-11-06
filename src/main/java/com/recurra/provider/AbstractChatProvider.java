package com.recurra.provider;

import com.recurra.config.RecurraProperties;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Abstract base class for chat providers with common functionality.
 */
@Slf4j
public abstract class AbstractChatProvider implements ChatProvider {

    protected final WebClient webClient;
    protected final RecurraProperties properties;
    protected final RecurraProperties.ProviderConfig config;

    protected AbstractChatProvider(
            WebClient webClient,
            RecurraProperties properties,
            String providerName) {
        this.webClient = webClient;
        this.properties = properties;
        this.config = properties.getProviders().get(providerName);
    }

    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    /**
     * Execute request with retry logic.
     */
    protected <T> Mono<T> executeWithRetry(Mono<T> request) {
        return request
                .retryWhen(Retry.backoff(properties.getProxy().getMaxRetries(), Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10))
                        .filter(this::isRetryable))
                .doOnSuccess(response -> log.debug("Request succeeded for provider: {}", getName()))
                .doOnError(error -> log.error("Request failed for provider: {}", getName(), error));
    }

    /**
     * Check if an error is retryable.
     */
    protected boolean isRetryable(Throwable throwable) {
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

    /**
     * Get provider configuration.
     */
    protected RecurraProperties.ProviderConfig getConfig() {
        return config;
    }
}
