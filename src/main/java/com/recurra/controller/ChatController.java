package com.recurra.controller;

import com.recurra.model.CacheControlContext;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.ChatCompletionResponse;
import com.recurra.service.CacheControlParser;
import com.recurra.service.ProxyService;
import com.recurra.service.StreamingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenAI-compatible chat completions controller with cache provenance headers.
 * Supports both regular and SSE streaming responses.
 */
@Slf4j
@RestController
@RequestMapping("/v1")
public class ChatController {

    private final ProxyService proxyService;
    private final StreamingService streamingService;
    private final CacheControlParser cacheControlParser;

    public ChatController(ProxyService proxyService,
                         StreamingService streamingService,
                         CacheControlParser cacheControlParser) {
        this.proxyService = proxyService;
        this.streamingService = streamingService;
        this.cacheControlParser = cacheControlParser;
    }

    /**
     * Chat completions endpoint - OpenAI compatible with cache provenance.
     * Supports both regular JSON responses and SSE streaming.
     */
    @PostMapping(value = "/chat/completions",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<?> createChatCompletion(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader HttpHeaders headers) {

        log.info("Received chat completion request for model: {}, stream: {}",
                request.getModel(), request.getStream());

        // Parse cache control headers
        CacheControlContext cacheContext = cacheControlParser.parse(headers);
        log.debug("Parsed cache control context: bypass={}, store={}, mode={}",
                cacheContext.isBypass(), cacheContext.isStore(), cacheContext.getMode());

        // Validate request
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Messages cannot be empty"));
        }

        if (request.getModel() == null || request.getModel().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Model must be specified"));
        }

        // Check if streaming is requested
        boolean isStreaming = request.getStream() != null && request.getStream();

        if (isStreaming) {
            return handleStreamingRequest(request, cacheContext);
        } else {
            return handleRegularRequest(request, cacheContext);
        }
    }

    /**
     * Handle regular (non-streaming) request.
     */
    private Mono<ResponseEntity<ChatCompletionResponse>> handleRegularRequest(
            ChatCompletionRequest request,
            CacheControlContext cacheContext) {
        return proxyService.processRequest(request, cacheContext)
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

                        if (proxyResponse.getCacheAgeSeconds() != null) {
                            headers.add("x-cache-age", String.valueOf(proxyResponse.getCacheAgeSeconds()));
                        }

                        if (proxyResponse.isDegraded()) {
                            headers.add("x-cache-degraded", "true");
                            if (proxyResponse.getDegradedReason() != null) {
                                headers.add("x-cache-degraded-reason", proxyResponse.getDegradedReason());
                            }
                        }
                    }

                    return ResponseEntity.ok()
                            .headers(headers)
                            .body(proxyResponse.getResponse());
                });
    }

    /**
     * Handle streaming request with SSE.
     */
    private Mono<ResponseEntity<Flux<String>>> handleStreamingRequest(
            ChatCompletionRequest request,
            CacheControlContext cacheContext) {
        return proxyService.processRequest(request, cacheContext)
                .map(proxyResponse -> {
                    // Build provenance headers
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.TEXT_EVENT_STREAM);
                    headers.setCacheControl("no-cache");
                    headers.setConnection("keep-alive");
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

                        if (proxyResponse.getCacheAgeSeconds() != null) {
                            headers.add("x-cache-age", String.valueOf(proxyResponse.getCacheAgeSeconds()));
                        }

                        if (proxyResponse.isDegraded()) {
                            headers.add("x-cache-degraded", "true");
                            if (proxyResponse.getDegradedReason() != null) {
                                headers.add("x-cache-degraded-reason", proxyResponse.getDegradedReason());
                            }
                        }
                    }

                    // Stream the response
                    Flux<String> stream = streamingService.streamResponse(
                            proxyResponse.getResponse(),
                            proxyResponse.isCacheHit()
                    );

                    return ResponseEntity.ok()
                            .headers(headers)
                            .body(stream);
                });
    }
}

