package com.recurra.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recurra.config.RecurraProperties;
import com.recurra.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * AWS Bedrock chat completion provider.
 * Supports Claude models via Bedrock Runtime API.
 */
@Slf4j
@Component
public class BedrockProvider extends AbstractChatProvider {

    private final ObjectMapper objectMapper;
    private BedrockRuntimeAsyncClient bedrockClient;

    public BedrockProvider(
            WebClient webClient,
            RecurraProperties properties,
            ObjectMapper objectMapper) {
        super(webClient, properties, "bedrock");
        this.objectMapper = objectMapper;
        initializeBedrockClient();
    }

    /**
     * Initialize Bedrock async client with AWS credentials.
     */
    private void initializeBedrockClient() {
        if (!isEnabled()) {
            log.debug("Bedrock provider is disabled, skipping client initialization");
            return;
        }

        try {
            String region = config.getRegion() != null ? config.getRegion() : "us-east-1";

            // Create credentials from API key (access key ID and secret key separated by colon)
            // Format: ACCESS_KEY_ID:SECRET_ACCESS_KEY
            String[] credentials = config.getApiKey().split(":", 2);
            if (credentials.length != 2) {
                log.error("Invalid Bedrock credentials format. Expected: ACCESS_KEY_ID:SECRET_ACCESS_KEY");
                return;
            }

            AwsBasicCredentials awsCreds = AwsBasicCredentials.create(
                    credentials[0],  // Access Key ID
                    credentials[1]   // Secret Access Key
            );

            bedrockClient = BedrockRuntimeAsyncClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                    .build();

            log.info("Bedrock client initialized for region: {}", region);

        } catch (Exception e) {
            log.error("Failed to initialize Bedrock client", e);
        }
    }

    @Override
    public String getName() {
        return "bedrock";
    }

    @Override
    public boolean supports(String model) {
        if (model == null) {
            return false;
        }

        String lowerModel = model.toLowerCase();
        // Support Bedrock model IDs and friendly names
        return lowerModel.contains("claude") ||
                lowerModel.contains("anthropic") ||
                lowerModel.startsWith("amazon.titan") ||
                lowerModel.startsWith("ai21") ||
                lowerModel.startsWith("cohere");
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        if (!isEnabled() || bedrockClient == null) {
            return Mono.error(new RuntimeException("Bedrock provider is not enabled or not configured"));
        }

        log.info("Forwarding request to Bedrock: model={}", request.getModel());

        try {
            // Convert OpenAI format to Bedrock format
            String bedrockModelId = resolveModelId(request.getModel());
            JsonNode bedrockRequest = convertToBedrockFormat(request, bedrockModelId);

            // Invoke Bedrock
            InvokeModelRequest invokeRequest = InvokeModelRequest.builder()
                    .modelId(bedrockModelId)
                    .body(SdkBytes.fromString(bedrockRequest.toString(), StandardCharsets.UTF_8))
                    .build();

            CompletableFuture<InvokeModelResponse> future = bedrockClient.invokeModel(invokeRequest);

            return Mono.fromFuture(future)
                    .map(response -> {
                        String responseBody = response.body().asUtf8String();
                        return convertToOpenAIFormat(responseBody, request.getModel(), bedrockModelId);
                    })
                    .doOnSuccess(response -> log.info("Received response from Bedrock"))
                    .doOnError(error -> log.error("Error invoking Bedrock", error));

        } catch (Exception e) {
            return Mono.error(new RuntimeException("Failed to invoke Bedrock: " + e.getMessage(), e));
        }
    }

    /**
     * Resolve friendly model name to Bedrock model ID.
     */
    private String resolveModelId(String model) {
        // Map friendly names to Bedrock model IDs
        if (model.equals("claude-3-opus") || model.equals("claude-3-opus-20240229")) {
            return "anthropic.claude-3-opus-20240229-v1:0";
        } else if (model.equals("claude-3-sonnet") || model.equals("claude-3-sonnet-20240229")) {
            return "anthropic.claude-3-sonnet-20240229-v1:0";
        } else if (model.equals("claude-3-haiku") || model.equals("claude-3-haiku-20240307")) {
            return "anthropic.claude-3-haiku-20240307-v1:0";
        } else if (model.equals("claude-2.1")) {
            return "anthropic.claude-v2:1";
        } else if (model.equals("claude-2")) {
            return "anthropic.claude-v2";
        } else if (model.startsWith("anthropic.")) {
            // Already a Bedrock model ID
            return model;
        }

        // Default to Claude 3 Sonnet
        log.warn("Unknown model {}, defaulting to Claude 3 Sonnet", model);
        return "anthropic.claude-3-sonnet-20240229-v1:0";
    }

    /**
     * Convert OpenAI request to Bedrock Claude format.
     */
    private JsonNode convertToBedrockFormat(ChatCompletionRequest request, String bedrockModelId) {
        ObjectNode bedrockRequest = objectMapper.createObjectNode();

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
            ObjectNode bedrockMsg = objectMapper.createObjectNode();
            bedrockMsg.put("role", msg.getRole());

            ObjectNode content = objectMapper.createObjectNode();
            content.put("type", "text");
            content.put("text", msg.getContent());

            ArrayNode contentArray = objectMapper.createArrayNode();
            contentArray.add(content);
            bedrockMsg.set("content", contentArray);

            messagesArray.add(bedrockMsg);
        }

        bedrockRequest.set("messages", messagesArray);

        // Add system message if present
        if (systemMessage != null) {
            bedrockRequest.put("system", systemMessage);
        }

        // Add inference parameters
        bedrockRequest.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : 4096);

        if (request.getTemperature() != null) {
            bedrockRequest.put("temperature", request.getTemperature());
        }

        if (request.getTopP() != null) {
            bedrockRequest.put("top_p", request.getTopP());
        }

        // Anthropic API version
        bedrockRequest.put("anthropic_version", "bedrock-2023-05-31");

        return bedrockRequest;
    }

    /**
     * Convert Bedrock Claude response to OpenAI format.
     */
    private ChatCompletionResponse convertToOpenAIFormat(
            String responseBody,
            String requestedModel,
            String bedrockModelId) {
        try {
            JsonNode bedrockResponse = objectMapper.readTree(responseBody);

            // Extract content from Claude response
            StringBuilder contentBuilder = new StringBuilder();
            JsonNode content = bedrockResponse.get("content");

            if (content != null && content.isArray()) {
                for (JsonNode item : content) {
                    if ("text".equals(item.get("type").asText())) {
                        contentBuilder.append(item.get("text").asText());
                    }
                }
            }

            String finishReason = bedrockResponse.has("stop_reason")
                    ? mapStopReason(bedrockResponse.get("stop_reason").asText())
                    : "stop";

            // Build OpenAI-compatible response
            Message assistantMessage = Message.builder()
                    .role("assistant")
                    .content(contentBuilder.toString())
                    .build();

            Choice choice = Choice.builder()
                    .index(0)
                    .message(assistantMessage)
                    .finishReason(finishReason)
                    .build();

            // Extract usage statistics
            Usage usage = null;
            if (bedrockResponse.has("usage")) {
                JsonNode usageNode = bedrockResponse.get("usage");
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
                    .id("chatcmpl-" + UUID.randomUUID().toString().substring(0, 8))
                    .object("chat.completion")
                    .created(Instant.now().getEpochSecond())
                    .model(requestedModel)  // Return the model name the user requested
                    .choices(List.of(choice))
                    .usage(usage)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Bedrock response", e);
            throw new RuntimeException("Failed to parse Bedrock response: " + e.getMessage(), e);
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
