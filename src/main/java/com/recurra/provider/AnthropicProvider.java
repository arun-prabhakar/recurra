package com.recurra.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recurra.config.RecurraProperties;
import com.recurra.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anthropic (Claude) chat completion provider.
 * Supports direct API access to Claude models.
 */
@Slf4j
@Component
public class AnthropicProvider extends AbstractChatProvider {

    private final ObjectMapper objectMapper;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    public AnthropicProvider(
            WebClient webClient,
            RecurraProperties properties,
            ObjectMapper objectMapper) {
        super(webClient, properties, "anthropic");
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "anthropic";
    }

    @Override
    public boolean supports(String model) {
        if (model == null) {
            return false;
        }

        String lowerModel = model.toLowerCase();
        return lowerModel.startsWith("claude");
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        if (!isEnabled()) {
            return Mono.error(new RuntimeException("Anthropic provider is not enabled"));
        }

        log.info("Forwarding request to Anthropic: model={}", request.getModel());

        try {
            // Convert OpenAI format to Anthropic format
            JsonNode anthropicRequest = convertToAnthropicFormat(request);
            String endpoint = config.getBaseUrl() + "/v1/messages";

            Mono<JsonNode> responseMono = webClient.post()
                    .uri(endpoint)
                    .header("x-api-key", config.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(anthropicRequest.toString())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(response -> convertToOpenAIFormat(response, request.getModel()));

            return executeWithRetry(responseMono);

        } catch (Exception e) {
            return Mono.error(new RuntimeException("Failed to invoke Anthropic: " + e.getMessage(), e));
        }
    }

    /**
     * Convert OpenAI request to Anthropic format.
     */
    private JsonNode convertToAnthropicFormat(ChatCompletionRequest request) {
        ObjectNode anthropicRequest = objectMapper.createObjectNode();

        // Model
        anthropicRequest.put("model", request.getModel());

        // Extract system message and user messages
        List<Message> messages = request.getMessages();
        String systemMessage = null;
        List<Message> conversationMessages = new ArrayList<>();

        for (Message msg : messages) {
            if ("system".equals(msg.getRole())) {
                systemMessage = msg.getContent();
            } else {
                conversationMessages.add(msg);
            }
        }

        // Build messages array
        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (Message msg : conversationMessages) {
            ObjectNode anthropicMsg = objectMapper.createObjectNode();
            anthropicMsg.put("role", msg.getRole());

            // Anthropic uses array of content blocks
            ArrayNode contentArray = objectMapper.createArrayNode();
            ObjectNode textContent = objectMapper.createObjectNode();
            textContent.put("type", "text");
            textContent.put("text", msg.getContent());
            contentArray.add(textContent);

            anthropicMsg.set("content", contentArray);
            messagesArray.add(anthropicMsg);
        }

        anthropicRequest.set("messages", messagesArray);

        // Add system message if present
        if (systemMessage != null) {
            anthropicRequest.put("system", systemMessage);
        }

        // Add inference parameters
        anthropicRequest.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : 4096);

        if (request.getTemperature() != null) {
            anthropicRequest.put("temperature", request.getTemperature());
        }

        if (request.getTopP() != null) {
            anthropicRequest.put("top_p", request.getTopP());
        }

        return anthropicRequest;
    }

    /**
     * Convert Anthropic response to OpenAI format.
     */
    private ChatCompletionResponse convertToOpenAIFormat(JsonNode anthropicResponse, String model) {
        try {
            // Extract content from Claude response
            StringBuilder contentBuilder = new StringBuilder();
            JsonNode content = anthropicResponse.get("content");

            if (content != null && content.isArray()) {
                for (JsonNode item : content) {
                    if ("text".equals(item.get("type").asText())) {
                        contentBuilder.append(item.get("text").asText());
                    }
                }
            }

            String finishReason = anthropicResponse.has("stop_reason")
                    ? mapStopReason(anthropicResponse.get("stop_reason").asText())
                    : "stop";

            // Build OpenAI-compatible response
            Message assistantMessage = Message.builder()
                    .role(anthropicResponse.has("role") ? anthropicResponse.get("role").asText() : "assistant")
                    .content(contentBuilder.toString())
                    .build();

            Choice choice = Choice.builder()
                    .index(0)
                    .message(assistantMessage)
                    .finishReason(finishReason)
                    .build();

            // Extract usage statistics
            Usage usage = null;
            if (anthropicResponse.has("usage")) {
                JsonNode usageNode = anthropicResponse.get("usage");
                usage = Usage.builder()
                        .promptTokens(usageNode.has("input_tokens") ? usageNode.get("input_tokens").asInt() : null)
                        .completionTokens(usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : null)
                        .totalTokens(
                                (usageNode.has("input_tokens") ? usageNode.get("input_tokens").asInt() : 0) +
                                (usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : 0)
                        )
                        .build();
            }

            return ChatCompletionResponse.builder()
                    .id(anthropicResponse.has("id")
                            ? "chatcmpl-" + anthropicResponse.get("id").asText()
                            : "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8))
                    .object("chat.completion")
                    .created(Instant.now().getEpochSecond())
                    .model(model)
                    .choices(List.of(choice))
                    .usage(usage)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Anthropic response", e);
            throw new RuntimeException("Failed to parse Anthropic response: " + e.getMessage(), e);
        }
    }

    /**
     * Map Claude stop reasons to OpenAI finish reasons.
     */
    private String mapStopReason(String claudeStopReason) {
        return switch (claudeStopReason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "stop_sequence" -> "stop";
            default -> "stop";
        };
    }

    @Override
    public boolean supportsStreaming() {
        // Streaming can be added in future
        return false;
    }
}
