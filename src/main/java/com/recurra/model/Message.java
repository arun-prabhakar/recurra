package com.recurra.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chat message model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {

    @JsonProperty("role")
    private String role; // system, user, assistant, function

    @JsonProperty("content")
    private String content;

    @JsonProperty("name")
    private String name;

    @JsonProperty("function_call")
    private Object functionCall;

    @JsonProperty("tool_calls")
    private Object toolCalls;
}
