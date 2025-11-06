package com.recurra.provider;

import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import reactor.core.publisher.Mono;

/**
 * Interface for chat completion providers.
 * Implementations handle provider-specific authentication, request/response mapping,
 * and API communication.
 */
public interface ChatProvider {

    /**
     * Get provider name (e.g., "openai", "bedrock", "anthropic").
     *
     * @return provider name
     */
    String getName();

    /**
     * Check if this provider supports the given model.
     *
     * @param model model name
     * @return true if supported
     */
    boolean supports(String model);

    /**
     * Complete a chat request.
     *
     * @param request OpenAI-compatible request
     * @return provider response (normalized to OpenAI format)
     */
    Mono<ChatCompletionResponse> complete(ChatCompletionRequest request);

    /**
     * Check if provider is enabled and configured.
     *
     * @return true if ready to use
     */
    boolean isEnabled();

    /**
     * Check if provider supports streaming.
     *
     * @return true if streaming is supported
     */
    default boolean supportsStreaming() {
        return false;
    }
}
