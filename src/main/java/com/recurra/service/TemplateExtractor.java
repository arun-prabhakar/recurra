package com.recurra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts structural templates from requests for cache matching.
 * This service normalizes requests by identifying patterns and structures
 * rather than exact content, enabling template-aware caching.
 */
@Slf4j
@Service
public class TemplateExtractor {

    private final ObjectMapper objectMapper;
    private final JaroWinklerSimilarity similarityCalculator;

    // Patterns for template extraction
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{[^}]+\\}|\\$\\{[^}]+\\}|\\$[a-zA-Z_][a-zA-Z0-9_]*");

    public TemplateExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.similarityCalculator = new JaroWinklerSimilarity();
    }

    /**
     * Generate a cache key based on exact match.
     */
    public String generateExactKey(ChatCompletionRequest request) {
        try {
            String normalized = normalizeRequest(request);
            return hashString(normalized);
        } catch (Exception e) {
            log.error("Error generating exact cache key", e);
            return null;
        }
    }

    /**
     * Generate a template-based structural key.
     */
    public String generateTemplateKey(ChatCompletionRequest request) {
        try {
            TemplateSignature signature = extractTemplate(request);
            return signature.toKey();
        } catch (Exception e) {
            log.error("Error generating template cache key", e);
            return null;
        }
    }

    /**
     * Extract structural template from a request.
     */
    public TemplateSignature extractTemplate(ChatCompletionRequest request) {
        TemplateSignature signature = new TemplateSignature();

        // Model signature
        signature.model = normalizeModel(request.getModel());

        // Message structure signature
        signature.messageStructure = extractMessageStructure(request.getMessages());

        // Parameter signature
        signature.temperature = normalizeTemperature(request.getTemperature());
        signature.hasTools = request.getTools() != null && !request.getTools().isEmpty();
        signature.hasFunctions = request.getFunctions() != null && !request.getFunctions().isEmpty();

        return signature;
    }

    /**
     * Calculate similarity between two requests.
     */
    public double calculateSimilarity(ChatCompletionRequest req1, ChatCompletionRequest req2) {
        TemplateSignature sig1 = extractTemplate(req1);
        TemplateSignature sig2 = extractTemplate(req2);

        // Calculate component similarities
        double modelSim = sig1.model.equals(sig2.model) ? 1.0 : 0.0;
        double structureSim = calculateStructuralSimilarity(sig1.messageStructure, sig2.messageStructure);
        double paramSim = calculateParameterSimilarity(sig1, sig2);

        // Weighted average
        return (modelSim * 0.3) + (structureSim * 0.5) + (paramSim * 0.2);
    }

    /**
     * Normalize message content by replacing specific values with placeholders.
     */
    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }

        String normalized = content;

        // Replace URLs
        normalized = URL_PATTERN.matcher(normalized).replaceAll("{URL}");

        // Replace emails
        normalized = EMAIL_PATTERN.matcher(normalized).replaceAll("{EMAIL}");

        // Replace UUIDs
        normalized = UUID_PATTERN.matcher(normalized).replaceAll("{UUID}");

        // Replace dates
        normalized = DATE_PATTERN.matcher(normalized).replaceAll("{DATE}");

        // Replace numbers (but preserve the pattern)
        normalized = NUMBER_PATTERN.matcher(normalized).replaceAll("{NUM}");

        return normalized;
    }

    /**
     * Extract message structure signature.
     */
    private String extractMessageStructure(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        return messages.stream()
                .map(msg -> {
                    String role = msg.getRole();
                    String normalizedContent = normalizeContent(msg.getContent());
                    int contentLength = normalizedContent.length();
                    boolean hasToolCalls = msg.getToolCalls() != null;
                    boolean hasFunctionCall = msg.getFunctionCall() != null;

                    // Create a structure signature
                    return String.format("%s:%d:%b:%b",
                            role,
                            contentLength / 100, // Bucket by 100 chars
                            hasToolCalls,
                            hasFunctionCall);
                })
                .collect(Collectors.joining("|"));
    }

    /**
     * Normalize model name.
     */
    private String normalizeModel(String model) {
        if (model == null) {
            return "default";
        }

        // Normalize model versions (e.g., gpt-4-0613 -> gpt-4)
        return model.replaceAll("-\\d{4}(-\\d{2})?(-\\d{2})?", "");
    }

    /**
     * Normalize temperature to buckets.
     */
    private String normalizeTemperature(Double temp) {
        if (temp == null) {
            return "default";
        }

        // Bucket temperatures
        if (temp < 0.3) return "low";
        if (temp < 0.7) return "medium";
        return "high";
    }

    /**
     * Calculate structural similarity between message structures.
     */
    private double calculateStructuralSimilarity(String struct1, String struct2) {
        if (struct1.equals(struct2)) {
            return 1.0;
        }

        return similarityCalculator.apply(struct1, struct2);
    }

    /**
     * Calculate parameter similarity.
     */
    private double calculateParameterSimilarity(TemplateSignature sig1, TemplateSignature sig2) {
        int matches = 0;
        int total = 3;

        if (sig1.temperature.equals(sig2.temperature)) matches++;
        if (sig1.hasTools == sig2.hasTools) matches++;
        if (sig1.hasFunctions == sig2.hasFunctions) matches++;

        return (double) matches / total;
    }

    /**
     * Normalize request to string for exact matching.
     */
    private String normalizeRequest(ChatCompletionRequest request) throws Exception {
        // Create a normalized copy
        ChatCompletionRequest normalized = ChatCompletionRequest.builder()
                .model(request.getModel())
                .messages(request.getMessages())
                .temperature(request.getTemperature())
                .topP(request.getTopP())
                .maxTokens(request.getMaxTokens())
                .build();

        return objectMapper.writeValueAsString(normalized);
    }

    /**
     * Hash a string to create a cache key.
     */
    private String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Template signature for structural matching.
     */
    public static class TemplateSignature {
        public String model;
        public String messageStructure;
        public String temperature;
        public boolean hasTools;
        public boolean hasFunctions;

        public String toKey() {
            return String.format("%s:%s:%s:%b:%b",
                    model, messageStructure, temperature, hasTools, hasFunctions);
        }
    }
}
