package com.recurra.controller;

import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import com.recurra.service.ProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * OpenAI-compatible chat completions controller with cache provenance headers.
 */
@Slf4j
@RestController
@RequestMapping("/v1")
public class ChatController {

    private final ProxyService proxyService;

    public ChatController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    /**
     * Chat completions endpoint - OpenAI compatible with cache provenance.
     */
    @PostMapping(value = "/chat/completions",
                 produces = MediaType.APPLICATION_JSON_VALUE,
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ChatCompletionResponse>> createChatCompletion(
            @RequestBody ChatCompletionRequest request) {

        log.info("Received chat completion request for model: {}", request.getModel());

        // Validate request
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Messages cannot be empty"));
        }

        if (request.getModel() == null || request.getModel().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Model must be specified"));
        }

        return proxyService.processRequest(request)
                .map(proxyResponse -> {
                    // Build response with provenance headers
                    HttpHeaders headers = new HttpHeaders();
                    headers.add("x-cache-hit", String.valueOf(proxyResponse.isCacheHit()));
                    headers.add("x-cache-match", proxyResponse.getMatchType());

                    if (proxyResponse.isCacheHit()) {
                        headers.add("x-cache-score", String.format("%.3f", proxyResponse.getScore()));

                        if (proxyResponse.getProvenanceId() != null) {
                            headers.add("x-cache-provenance", proxyResponse.getProvenanceId());
                        }

                        if (proxyResponse.getSourceModel() != null) {
                            headers.add("x-cache-source-model", proxyResponse.getSourceModel());
                        }
                    }

                    return ResponseEntity.ok()
                            .headers(headers)
                            .body(proxyResponse.getResponse());
                });
    }
}

