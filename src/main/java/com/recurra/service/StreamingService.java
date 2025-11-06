package com.recurra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recurra.config.RecurraProperties;
import com.recurra.model.ChatCompletionChunk;
import com.recurra.model.ChatCompletionResponse;
import com.recurra.model.Choice;
import com.recurra.model.Delta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for converting cached responses to SSE streaming format.
 * Provides deterministic replay - same response always produces same chunks.
 */
@Slf4j
@Service
public class StreamingService {

    private final RecurraProperties properties;
    private final ObjectMapper objectMapper;

    // Default chunk size (characters per chunk)
    private static final int DEFAULT_CHUNK_SIZE = 8;

    // Default delay between chunks (milliseconds)
    private static final int DEFAULT_CHUNK_DELAY_MS = 20;

    public StreamingService(RecurraProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Convert a cached response to a stream of SSE chunks.
     * Deterministic - same input always produces same output chunks.
     *
     * @param response cached completion response
     * @param cacheHit whether this is from cache
     * @return Flux of SSE-formatted chunks
     */
    public Flux<String> streamResponse(ChatCompletionResponse response, boolean cacheHit) {
        List<ChatCompletionChunk> chunks = chunkResponse(response);

        // Adjust delay based on cache hit (cached responses can stream faster)
        int delayMs = cacheHit ? DEFAULT_CHUNK_DELAY_MS / 2 : DEFAULT_CHUNK_DELAY_MS;

        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(delayMs))
                .map(this::formatAsSSE)
                .concatWith(Flux.just("data: [DONE]\n\n"))
                .doOnComplete(() -> log.debug("Streaming completed for response {}", response.getId()));
    }

    /**
     * Chunk a complete response into streaming chunks.
     * Deterministic chunking based on content length.
     */
    private List<ChatCompletionChunk> chunkResponse(ChatCompletionResponse response) {
        List<ChatCompletionChunk> chunks = new ArrayList<>();

        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            // Empty response - just send a final chunk
            chunks.add(createFinalChunk(response, 0, ""));
            return chunks;
        }

        // Process each choice
        for (int choiceIndex = 0; choiceIndex < response.getChoices().size(); choiceIndex++) {
            Choice choice = response.getChoices().get(choiceIndex);
            String content = choice.getMessage() != null ? choice.getMessage().getContent() : "";
            String role = choice.getMessage() != null ? choice.getMessage().getRole() : "assistant";

            if (content == null) {
                content = "";
            }

            // First chunk includes role
            if (choiceIndex == 0) {
                chunks.add(createRoleChunk(response, choiceIndex, role));
            }

            // Split content into deterministic chunks
            List<String> contentChunks = splitContentDeterministically(content);

            for (int i = 0; i < contentChunks.size(); i++) {
                chunks.add(createContentChunk(response, choiceIndex, contentChunks.get(i)));
            }

            // Final chunk with finish_reason
            String finishReason = choice.getFinishReason() != null ? choice.getFinishReason() : "stop";
            chunks.add(createFinalChunk(response, choiceIndex, finishReason));
        }

        return chunks;
    }

    /**
     * Split content into deterministic chunks.
     * Uses word boundaries to avoid splitting words when possible.
     */
    private List<String> splitContentDeterministically(String content) {
        List<String> chunks = new ArrayList<>();

        if (content.isEmpty()) {
            return chunks;
        }

        int pos = 0;
        while (pos < content.length()) {
            int endPos = Math.min(pos + DEFAULT_CHUNK_SIZE, content.length());

            // Try to split at word boundary
            if (endPos < content.length()) {
                // Look for space within next 3 characters
                for (int i = endPos; i < Math.min(endPos + 3, content.length()); i++) {
                    if (Character.isWhitespace(content.charAt(i))) {
                        endPos = i + 1;
                        break;
                    }
                }
            }

            chunks.add(content.substring(pos, endPos));
            pos = endPos;
        }

        return chunks;
    }

    /**
     * Create first chunk with role.
     */
    private ChatCompletionChunk createRoleChunk(ChatCompletionResponse response, int index, String role) {
        return ChatCompletionChunk.builder()
                .id(response.getId())
                .object("chat.completion.chunk")
                .created(response.getCreated())
                .model(response.getModel())
                .systemFingerprint(response.getSystemFingerprint())
                .choices(List.of(
                        ChatCompletionChunk.ChunkChoice.builder()
                                .index(index)
                                .delta(Delta.builder()
                                        .role(role)
                                        .build())
                                .finishReason(null)
                                .build()
                ))
                .build();
    }

    /**
     * Create content chunk.
     */
    private ChatCompletionChunk createContentChunk(ChatCompletionResponse response, int index, String content) {
        return ChatCompletionChunk.builder()
                .id(response.getId())
                .object("chat.completion.chunk")
                .created(response.getCreated())
                .model(response.getModel())
                .systemFingerprint(response.getSystemFingerprint())
                .choices(List.of(
                        ChatCompletionChunk.ChunkChoice.builder()
                                .index(index)
                                .delta(Delta.builder()
                                        .content(content)
                                        .build())
                                .finishReason(null)
                                .build()
                ))
                .build();
    }

    /**
     * Create final chunk with finish_reason.
     */
    private ChatCompletionChunk createFinalChunk(ChatCompletionResponse response, int index, String finishReason) {
        return ChatCompletionChunk.builder()
                .id(response.getId())
                .object("chat.completion.chunk")
                .created(response.getCreated())
                .model(response.getModel())
                .systemFingerprint(response.getSystemFingerprint())
                .choices(List.of(
                        ChatCompletionChunk.ChunkChoice.builder()
                                .index(index)
                                .delta(Delta.builder().build())  // Empty delta
                                .finishReason(finishReason)
                                .build()
                ))
                .build();
    }

    /**
     * Format chunk as SSE (Server-Sent Events) message.
     */
    private String formatAsSSE(ChatCompletionChunk chunk) {
        try {
            // Convert to JSON (using Jackson via toString - simplified)
            // In production, use ObjectMapper for proper JSON serialization
            String json = serializeToJson(chunk);
            return "data: " + json + "\n\n";
        } catch (Exception e) {
            log.error("Error formatting chunk as SSE", e);
            return "data: {\"error\": \"serialization_error\"}\n\n";
        }
    }

    /**
     * Serialize chunk to JSON using ObjectMapper.
     */
    private String serializeToJson(ChatCompletionChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            log.error("JSON serialization error", e);
            return "{}";
        }
    }
}
