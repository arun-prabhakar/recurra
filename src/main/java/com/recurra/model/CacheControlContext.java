package com.recurra.model;

import lombok.Builder;
import lombok.Data;

/**
 * Context for cache control preferences from request headers.
 *
 * Allows per-request overrides of cache behavior:
 * - Bypass cache completely
 * - Prevent storage of response
 * - Prefer exact or template matching
 * - Override model compatibility rules
 * - Tag requests for A/B testing
 */
@Data
@Builder
public class CacheControlContext {

    /**
     * Whether to bypass cache lookup entirely.
     * If true, always forward to provider (cache miss).
     *
     * Default: false
     */
    @Builder.Default
    private boolean bypass = false;

    /**
     * Whether to store the response in cache.
     * If false, response won't be cached after provider call.
     *
     * Use case: Sensitive queries that shouldn't be cached.
     *
     * Default: true
     */
    @Builder.Default
    private boolean store = true;

    /**
     * Preferred cache mode.
     */
    @Builder.Default
    private CacheMode mode = CacheMode.BOTH;

    /**
     * Model compatibility policy.
     * Currently not enforced (future enhancement).
     */
    @Builder.Default
    private ModelCompatPolicy modelCompat = ModelCompatPolicy.STRICT;

    /**
     * Experiment label for A/B testing.
     * Can be used to segment cache by experiment variant.
     */
    private String experiment;

    /**
     * Cache mode preference.
     */
    public enum CacheMode {
        /**
         * Only exact matches (Redis cache).
         */
        EXACT,

        /**
         * Only template matches (Postgres cache).
         */
        TEMPLATE,

        /**
         * Both exact and template (default).
         */
        BOTH
    }

    /**
     * Model compatibility policy.
     *
     * Determines whether cached responses from similar models can be reused.
     * Currently not enforced - future enhancement for Phase 4.
     */
    public enum ModelCompatPolicy {
        /**
         * Exact model name match required.
         * gpt-4 != gpt-4-turbo
         */
        STRICT,

        /**
         * Same model family allowed.
         * gpt-4 == gpt-4-turbo == gpt-4-0613
         */
        FAMILY,

        /**
         * Any model (dangerous, not recommended).
         */
        ANY
    }

    /**
     * Create default context (no overrides).
     */
    public static CacheControlContext defaults() {
        return CacheControlContext.builder().build();
    }

    /**
     * Check if cache lookup should be performed.
     */
    public boolean shouldLookup() {
        return !bypass;
    }

    /**
     * Check if exact cache should be used.
     */
    public boolean useExactCache() {
        return mode == CacheMode.EXACT || mode == CacheMode.BOTH;
    }

    /**
     * Check if template cache should be used.
     */
    public boolean useTemplateCache() {
        return mode == CacheMode.TEMPLATE || mode == CacheMode.BOTH;
    }
}
