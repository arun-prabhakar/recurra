package com.recurra.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat completion request model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("messages")
    private List<Message> messages;

    @JsonProperty("temperature")
    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("n")
    private Integer n;

    @JsonProperty("stream")
    private Boolean stream;

    @JsonProperty("stop")
    private Object stop; // Can be String or List<String>

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    @JsonProperty("logit_bias")
    private Map<String, Integer> logitBias;

    @JsonProperty("user")
    private String user;

    @JsonProperty("functions")
    private List<Object> functions;

    @JsonProperty("function_call")
    private Object functionCall;

    @JsonProperty("tools")
    private List<Object> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice;
}
