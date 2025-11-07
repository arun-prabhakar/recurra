package com.recurra.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary view of a cache entry for list endpoints.
 * Contains minimal information for efficient browsing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheEntrySummary {

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
     * First 100 characters of canonical prompt.
     */
    private String promptPreview;

    /**
     * Request mode (text, json, tools, etc.).
     */
    private String mode;

    /**
     * SimHash fingerprint (hex string).
     */
    private String simhash;

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
     * Response token count (for cost estimation).
     */
    private Integer responseTokens;

    /**
     * Match score (for template matches).
     */
    private Double score;
}
