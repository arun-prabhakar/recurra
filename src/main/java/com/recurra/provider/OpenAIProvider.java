package com.recurra.provider;

import com.recurra.config.RecurraProperties;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * OpenAI chat completion provider.
 * Supports GPT-3.5, GPT-4, and other OpenAI models.
 */
@Slf4j
@Component
public class OpenAIProvider extends AbstractChatProvider {

    public OpenAIProvider(WebClient webClient, RecurraProperties properties) {
        super(webClient, properties, "openai");
    }

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public boolean supports(String model) {
        if (model == null) {
            return false;
        }

        String lowerModel = model.toLowerCase();
        return lowerModel.startsWith("gpt-") ||
                lowerModel.startsWith("text-") ||
                lowerModel.startsWith("davinci") ||
                lowerModel.contains("turbo");
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        if (!isEnabled()) {
            return Mono.error(new RuntimeException("OpenAI provider is not enabled"));
        }

        log.info("Forwarding request to OpenAI: model={}", request.getModel());

        String endpoint = config.getBaseUrl() + "/chat/completions";

        Mono<ChatCompletionResponse> responseMono = webClient.post()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class);

        return executeWithRetry(responseMono);
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }
}
