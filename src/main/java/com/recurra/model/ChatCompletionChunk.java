package com.recurra.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenAI-compatible streaming chat completion chunk.
 * Sent as SSE events during streaming responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionChunk {

    @JsonProperty("id")
    private String id;

    @JsonProperty("object")
    private String object; // Always "chat.completion.chunk"

    @JsonProperty("created")
    private Long created;

    @JsonProperty("model")
    private String model;

    @JsonProperty("choices")
    private List<ChunkChoice> choices;

    @JsonProperty("system_fingerprint")
    private String systemFingerprint;

    /**
     * Choice for streaming chunk with delta instead of message.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChunkChoice {

        @JsonProperty("index")
        private Integer index;

        @JsonProperty("delta")
        private Delta delta;

        @JsonProperty("finish_reason")
        private String finishReason;  // null until final chunk

        @JsonProperty("logprobs")
        private Object logprobs;
    }
}
