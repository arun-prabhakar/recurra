package com.recurra.model;

/**
 * HTTP headers for cache control and provenance.
 *
 * Request headers allow clients to control cache behavior per request.
 * Response headers provide transparency about cache hits and provenance.
 */
public class CacheHeaders {

    // ========== Request Control Headers ==========

    /**
     * Bypass cache lookup (always forward to provider).
     * Value: "true" or "false"
     *
     * Example: x-cache-bypass: true
     */
    public static final String CACHE_BYPASS = "x-cache-bypass";

    /**
     * Control whether response should be cached.
     * Value: "true" or "false"
     *
     * Example: x-cache-store: false (don't cache this response)
     */
    public static final String CACHE_STORE = "x-cache-store";

    /**
     * Cache mode preference.
     * Values: "exact", "template", "both"
     *
     * Example: x-cache-mode: exact (only exact matches)
     */
    public static final String CACHE_MODE = "x-cache-mode";

    /**
     * Model compatibility policy.
     * Values: "strict", "family", "any"
     *
     * Example: x-model-compat: family (allow gpt-4 variants)
     */
    public static final String MODEL_COMPAT = "x-model-compat";

    /**
     * Experiment label for A/B testing.
     * Value: any string identifier
     *
     * Example: x-cache-experiment: variant-a
     */
    public static final String EXPERIMENT = "x-cache-experiment";

    // ========== Response Provenance Headers ==========

    /**
     * Whether the response came from cache.
     * Value: "true" or "false"
     */
    public static final String CACHE_HIT = "x-cache-hit";

    /**
     * Type of cache match.
     * Values: "exact", "template", "none"
     */
    public static final String CACHE_MATCH = "x-cache-match";

    /**
     * Similarity score for template matches.
     * Value: 0.0-1.0 (string formatted to 3 decimals)
     *
     * Only present for template matches.
     */
    public static final String CACHE_SCORE = "x-cache-score";

    /**
     * ID of the cached entry (for debugging/auditing).
     * Value: UUID string
     *
     * Only present for cache hits.
     */
    public static final String CACHE_PROVENANCE = "x-cache-provenance";

    /**
     * Original model that created the cached response.
     * Value: model name string
     *
     * Only present for cache hits.
     */
    public static final String CACHE_SOURCE_MODEL = "x-cache-source-model";

    /**
     * Age of cached entry in seconds.
     * Value: integer seconds
     *
     * Only present for cache hits.
     */
    public static final String CACHE_AGE = "x-cache-age";

    /**
     * Whether cache is operating in degraded mode.
     * Value: "true" or "false"
     *
     * Present when Redis or Postgres is down.
     */
    public static final String CACHE_DEGRADED = "x-cache-degraded";

    /**
     * Reason for degraded mode.
     * Values: "redis-unavailable", "postgres-unavailable", "both-unavailable", etc.
     *
     * Only present when x-cache-degraded: true
     */
    public static final String CACHE_DEGRADED_REASON = "x-cache-degraded-reason";

    private CacheHeaders() {
        // Utility class, no instantiation
    }
}
