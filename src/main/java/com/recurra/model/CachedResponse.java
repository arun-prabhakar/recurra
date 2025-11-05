package com.recurra.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Cached response wrapper for Redis storage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The actual chat completion response.
     */
    private ChatCompletionResponse response;

    /**
     * Metadata about the cached entry.
     */
    private CacheMetadata metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheMetadata implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * When this entry was created.
         */
        private Instant createdAt;

        /**
         * Number of times this entry was hit.
         */
        private Integer hitCount;

        /**
         * Last time this entry was accessed.
         */
        private Instant lastHitAt;

        /**
         * Original model from request.
         */
        private String sourceModel;

        /**
         * Request mode (text, json, tools).
         */
        private String mode;

        /**
         * Tool schema hash if applicable.
         */
        private String toolSchemaHash;

        /**
         * Temperature bucket.
         */
        private String temperatureBucket;

        /**
         * Whether this is a golden entry.
         */
        private Boolean isGolden;
    }
}
