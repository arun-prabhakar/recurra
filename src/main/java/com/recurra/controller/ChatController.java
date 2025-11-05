package com.recurra.controller;

import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import com.recurra.service.ProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * OpenAI-compatible chat completions controller.
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
     * Chat completions endpoint - OpenAI compatible.
     */
    @PostMapping(value = "/chat/completions",
                 produces = MediaType.APPLICATION_JSON_VALUE,
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatCompletionResponse> createChatCompletion(
            @RequestBody ChatCompletionRequest request) {

        log.info("Received chat completion request for model: {}", request.getModel());

        // Validate request
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Messages cannot be empty"));
        }

        if (request.getModel() == null || request.getModel().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Model must be specified"));
        }

        return proxyService.processRequest(request);
    }
}
