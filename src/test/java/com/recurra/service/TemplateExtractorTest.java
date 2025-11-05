package com.recurra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recurra.model.ChatCompletionRequest;
import com.recurra.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TemplateExtractor.
 */
class TemplateExtractorTest {

    private TemplateExtractor templateExtractor;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        templateExtractor = new TemplateExtractor(objectMapper);
    }

    @Test
    void testGenerateExactKey() {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-4")
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("Hello, world!")
                                .build()
                ))
                .temperature(0.7)
                .build();

        String key = templateExtractor.generateExactKey(request);
        assertNotNull(key);
        assertFalse(key.isEmpty());
    }

    @Test
    void testGenerateTemplateKey() {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-4")
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("Summarize this article: https://example.com/article-123")
                                .build()
                ))
                .build();

        String templateKey = templateExtractor.generateTemplateKey(request);
        assertNotNull(templateKey);
        assertFalse(templateKey.isEmpty());
    }

    @Test
    void testCalculateSimilarity() {
        ChatCompletionRequest request1 = ChatCompletionRequest.builder()
                .model("gpt-4")
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("Summarize this article: https://example.com/article-123")
                                .build()
                ))
                .temperature(0.7)
                .build();

        ChatCompletionRequest request2 = ChatCompletionRequest.builder()
                .model("gpt-4")
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("Summarize this article: https://example.com/article-456")
                                .build()
                ))
                .temperature(0.7)
                .build();

        double similarity = templateExtractor.calculateSimilarity(request1, request2);

        // Should be highly similar (same structure, different URL)
        assertTrue(similarity > 0.8, "Similarity should be high for structurally similar requests");
    }

    @Test
    void testCalculateSimilarityDifferentStructures() {
        ChatCompletionRequest request1 = ChatCompletionRequest.builder()
                .model("gpt-4")
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("What is the weather?")
                                .build()
                ))
                .build();

        ChatCompletionRequest request2 = ChatCompletionRequest.builder()
                .model("gpt-4")
                .messages(List.of(
                        Message.builder()
                                .role("system")
                                .content("You are a helpful assistant.")
                                .build(),
                        Message.builder()
                                .role("user")
                                .content("Write a poem about coding")
                                .build()
                ))
                .build();

        double similarity = templateExtractor.calculateSimilarity(request1, request2);

        // Should have lower similarity (different structures)
        assertTrue(similarity < 0.8, "Similarity should be lower for different structures");
    }

    @Test
    void testExtractTemplate() {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-4-0613")
                .messages(List.of(
                        Message.builder()
                                .role("user")
                                .content("Hello!")
                                .build()
                ))
                .temperature(0.3)
                .build();

        TemplateExtractor.TemplateSignature signature = templateExtractor.extractTemplate(request);

        assertNotNull(signature);
        assertEquals("gpt-4", signature.model); // Version should be normalized
        assertEquals("low", signature.temperature); // Should be bucketed
        assertFalse(signature.hasTools);
        assertFalse(signature.hasFunctions);
    }
}
