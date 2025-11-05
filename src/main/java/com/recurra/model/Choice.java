package com.recurra.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Choice in chat completion response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Choice {

    @JsonProperty("index")
    private Integer index;

    @JsonProperty("message")
    private Message message;

    @JsonProperty("delta")
    private Message delta; // For streaming responses

    @JsonProperty("finish_reason")
    private String finishReason; // stop, length, function_call, content_filter, null

    @JsonProperty("logprobs")
    private Object logprobs;
}
