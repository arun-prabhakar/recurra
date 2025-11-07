package com.recurra.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Detailed view of a cache entry for single-entry endpoints.
 * Contains full information including request/response JSON.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheEntryDetail {

    /**
     * Cache entry ID.
     */
    private UUID id;

    /**
     * Tenant identifier.
     */
    private String tenant;

    /**
     * Model name.
     */
    private String model;

    /**
     * Full canonical prompt text.
     */
    private String canonicalPrompt;

    /**
     * Request mode (text, json, tools, etc.).
     */
    private String mode;

    /**
     * Tool schema hash (if applicable).
     */
    private String toolSchemaHash;

    /**
     * SimHash fingerprint (hex string).
     */
    private String simhash;

    /**
     * Temperature bucket (e.g., "0.0-0.3").
     */
    private String temperatureBucket;

    /**
     * Full request JSON.
     */
    private JsonNode requestJson;

    /**
     * Full response JSON.
     */
    private JsonNode responseJson;

    /**
     * Embedding vector (384 dimensions).
     */
    private float[] embedding;

    /**
     * Number of times this entry was hit.
     */
    private Integer hitCount;

    /**
     * When the entry was created.
     */
    private Instant createdAt;

    /**
     * Last time the entry was accessed.
     */
    private Instant lastHitAt;

    /**
     * Whether this is a golden (high-quality) entry.
     */
    private Boolean isGolden;

    /**
     * Request token count.
     */
    private Integer requestTokens;

    /**
     * Response token count.
     */
    private Integer responseTokens;

    /**
     * Estimated cost in USD (based on model pricing).
     */
    private Double estimatedCost;
}
